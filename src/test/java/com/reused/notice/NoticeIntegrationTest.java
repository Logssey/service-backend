package com.reused.notice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.reused.TestcontainersConfiguration;
import com.reused.auth.client.OAuthProviderClient;
import com.reused.auth.mail.AuthMailSender;
import com.reused.common.pagination.CursorCodec;

/**
 * 공개 공지 조회(목록·상세) 통합 테스트. 공지는 정렬을 통제하려고 SQL로 직접 넣는다(created_at을 지정).
 *
 * <p>목록 정렬은 {@code is_pinned DESC, created_at DESC, notice_id DESC}, 커서는 {@code {"p","t","id"}}다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class NoticeIntegrationTest {

	private static final String NOTICES = "/api/v1/notices";
	private static final Instant BASE = Instant.parse("2026-03-14T00:00:00Z");
	private static final String INVALID_CURSOR_MESSAGE = "커서가 올바르지 않습니다.";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private StringRedisTemplate redisTemplate;

	@MockitoBean
	private OAuthProviderClient kakaoOAuthClient;

	@MockitoBean
	private AuthMailSender mailSender;

	private long authorId;

	@BeforeEach
	void resetState() {
		jdbcTemplate.execute("TRUNCATE audit_logs, notifications, notices, notification_settings, "
				+ "user_status_histories, user_identities, users RESTART IDENTITY CASCADE");
		redisTemplate.execute((RedisCallback<Void>) connection -> {
			connection.serverCommands().flushDb();
			return null;
		});
		authorId = jdbcTemplate.queryForObject("INSERT INTO users (nickname, role, terms_agreed_at) "
				+ "VALUES ('운영자', 'ADMIN', now()) RETURNING user_id", Long.class);
	}

	// --- 목록 ---

	@Test
	@DisplayName("공지가 없으면 빈 목록이고 nextCursor는 생략하지 않고 null이다")
	void emptyList() throws Exception {
		JsonNode body = readBody(mockMvc.perform(get(NOTICES)).andExpect(status().isOk()).andReturn());

		assertThat(keys(body)).containsExactlyInAnyOrder("items", "nextCursor", "hasNext");
		assertThat(body.get("items").isEmpty()).isTrue();
		assertThat(body.get("nextCursor").isNull()).isTrue();
		assertThat(body.get("hasNext").booleanValue()).isFalse();
	}

	@Test
	@DisplayName("고정 공지가 먼저, 그룹 안에서는 최신순, 같은 시각이면 noticeId 내림차순이다. 삭제된 공지는 빠진다")
	void listOrdersPinnedFirstThenNewest() throws Exception {
		long n1 = insertNotice("일반 1시", false, BASE.plus(1, ChronoUnit.HOURS));
		long n2 = insertNotice("고정 0시", true, BASE);
		long n3 = insertNotice("일반 2시 A", false, BASE.plus(2, ChronoUnit.HOURS));
		long n4 = insertNotice("고정 3시", true, BASE.plus(3, ChronoUnit.HOURS));
		long n5 = insertNotice("일반 2시 B", false, BASE.plus(2, ChronoUnit.HOURS));
		long deleted = insertNotice("삭제된 고정", true, BASE.plus(5, ChronoUnit.HOURS));
		jdbcTemplate.update("UPDATE notices SET deleted_at = now() WHERE notice_id = ?", deleted);

		JsonNode body = readBody(mockMvc.perform(get(NOTICES)).andExpect(status().isOk()).andReturn());

		assertThat(ids(body)).containsExactly(n4, n2, n5, n3, n1);
		assertThat(body.get("hasNext").booleanValue()).isFalse();
		assertThat(body.get("nextCursor").isNull()).isTrue();
	}

	@Test
	@DisplayName("목록 항목은 noticeId·title·isPinned·createdAt만 담는다. pinned·content·authorId는 없다")
	void listItemFields() throws Exception {
		long noticeId = insertNotice("서비스 점검 안내", true, BASE);

		MvcResult result = mockMvc.perform(get(NOTICES))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].noticeId").value(noticeId))
				.andExpect(jsonPath("$.items[0].title").value("서비스 점검 안내"))
				.andExpect(jsonPath("$.items[0].isPinned").value(true))
				.andExpect(jsonPath("$.items[0].createdAt").value("2026-03-14T00:00:00Z"))
				.andReturn();

		assertThat(keys(readBody(result).get("items").get(0)))
				.containsExactlyInAnyOrder("noticeId", "title", "isPinned", "createdAt");
	}

	@Test
	@DisplayName("size를 생략하면 20건이다. 21건이면 다음 페이지에 1건이 남는다")
	void defaultPageSizeIsTwenty() throws Exception {
		for (int i = 0; i < 21; i++) {
			insertNotice("공지 " + i, false, BASE.plus(i, ChronoUnit.MINUTES));
		}

		JsonNode first = readBody(mockMvc.perform(get(NOTICES)).andExpect(status().isOk()).andReturn());
		assertThat(first.get("items")).hasSize(20);
		assertThat(first.get("hasNext").booleanValue()).isTrue();
		assertThat(first.get("nextCursor").isString()).isTrue();

		JsonNode second = readBody(mockMvc.perform(get(NOTICES).param("cursor", first.get("nextCursor").asString()))
				.andExpect(status().isOk()).andReturn());
		assertThat(ids(second)).containsExactly(1L);
		assertThat(second.get("hasNext").booleanValue()).isFalse();
		assertThat(second.get("nextCursor").isNull()).isTrue();
	}

	@Test
	@DisplayName("size=2로 이어 받으면 고정·일반 경계와 같은 시각을 넘어가도 중복·누락이 없다")
	void pagesAcrossPinnedBoundary() throws Exception {
		long n1 = insertNotice("일반 1시", false, BASE.plus(1, ChronoUnit.HOURS));
		long n2 = insertNotice("고정 0시", true, BASE);
		long n3 = insertNotice("일반 2시 A", false, BASE.plus(2, ChronoUnit.HOURS));
		long n4 = insertNotice("고정 3시", true, BASE.plus(3, ChronoUnit.HOURS));
		long n5 = insertNotice("일반 2시 B", false, BASE.plus(2, ChronoUnit.HOURS));

		List<List<Long>> pages = collectPages(2);

		assertThat(pages).containsExactly(List.of(n4, n2), List.of(n5, n3), List.of(n1));
	}

	@Test
	@DisplayName("커서는 createdAt을 마이크로초까지 보존한다. 같은 밀리초 안의 공지도 빠지지 않는다")
	void cursorKeepsMicroseconds() throws Exception {
		long older = insertNotice("먼저", false, BASE.plusNanos(1_000));
		long newer = insertNotice("나중", false, BASE.plusNanos(2_000));
		long newest = insertNotice("가장 나중", false, BASE.plusNanos(3_000));

		assertThat(collectPages(1)).containsExactly(List.of(newest), List.of(newer), List.of(older));
	}

	@Test
	@DisplayName("표준 Base64(패딩 포함)로 바꾼 커서도 같은 다음 페이지를 준다")
	void standardBase64CursorIsAccepted() throws Exception {
		insertNotice("A", false, BASE);
		long second = insertNotice("B", false, BASE.minus(1, ChronoUnit.HOURS));
		insertNotice("C", false, BASE.plus(1, ChronoUnit.HOURS));
		JsonNode first = readBody(mockMvc.perform(get(NOTICES).param("size", "2")).andReturn());
		String urlSafe = first.get("nextCursor").asString();
		String standard = Base64.getEncoder().encodeToString(Base64.getUrlDecoder().decode(urlSafe));

		JsonNode next = readBody(mockMvc.perform(get(NOTICES).param("size", "2").param("cursor", standard))
				.andExpect(status().isOk()).andReturn());

		assertThat(ids(next)).containsExactly(second);
	}

	@ParameterizedTest(name = "size={0}")
	@ValueSource(strings = { "0", "-1", "101", "abc", "1.5" })
	@DisplayName("size가 1~100 밖이거나 숫자가 아니면 잘라내지 않고 400이다")
	void sizeOutOfRange(String size) throws Exception {
		mockMvc.perform(get(NOTICES).param("size", size))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"));
	}

	@ParameterizedTest(name = "size={0}")
	@ValueSource(ints = { 1, 100 })
	@DisplayName("size 경계값 1과 100은 허용한다")
	void sizeBoundaries(int size) throws Exception {
		insertNotice("A", false, BASE);
		insertNotice("B", false, BASE.plus(1, ChronoUnit.HOURS));

		mockMvc.perform(get(NOTICES).param("size", String.valueOf(size)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(Math.min(size, 2)))
				.andExpect(jsonPath("$.hasNext").value(size < 2));
	}

	@Test
	@DisplayName("빈 cursor는 첫 페이지로 본다")
	void blankCursorMeansFirstPage() throws Exception {
		long noticeId = insertNotice("A", false, BASE);

		mockMvc.perform(get(NOTICES).param("cursor", ""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].noticeId").value(noticeId));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("invalidCursors")
	@DisplayName("해석할 수 없는 커서는 400 INVALID_INPUT이다")
	void invalidCursor(String description, String cursor) throws Exception {
		insertNotice("A", false, BASE);

		mockMvc.perform(get(NOTICES).param("cursor", cursor))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"))
				.andExpect(jsonPath("$.message").value(INVALID_CURSOR_MESSAGE));
	}

	static Stream<Arguments> invalidCursors() {
		String t = BASE.toString();
		return Stream.of(
				Arguments.of("Base64 아님", "!!not-base64!!"),
				Arguments.of("JSON 아님", Base64.getUrlEncoder().withoutPadding()
						.encodeToString("hello".getBytes(StandardCharsets.UTF_8))),
				Arguments.of("JSON 배열", Base64.getUrlEncoder().withoutPadding()
						.encodeToString("[1,2]".getBytes(StandardCharsets.UTF_8))),
				Arguments.of("다른 목록의 id 커서", CursorCodec.encodeId(1)),
				Arguments.of("키 누락", CursorCodec.encode(Map.of("p", true, "id", 1))),
				Arguments.of("키 추가", CursorCodec.encode(Map.of("p", true, "t", t, "id", 1, "x", 1))),
				Arguments.of("p가 문자열", CursorCodec.encode(Map.of("p", "true", "t", t, "id", 1))),
				Arguments.of("t가 숫자", CursorCodec.encode(Map.of("p", true, "t", 1, "id", 1))),
				Arguments.of("t가 시각 아님", CursorCodec.encode(Map.of("p", true, "t", "yesterday", "id", 1))),
				Arguments.of("t가 범위 밖", CursorCodec.encode(Map.of("p", true, "t", "+100000-01-01T00:00:00Z", "id", 1))),
				Arguments.of("id가 0", CursorCodec.encode(Map.of("p", true, "t", t, "id", 0))),
				Arguments.of("id가 음수", CursorCodec.encode(Map.of("p", true, "t", t, "id", -5))),
				Arguments.of("id가 소수", CursorCodec.encode(Map.of("p", true, "t", t, "id", 1.5))),
				Arguments.of("id가 문자열", CursorCodec.encode(Map.of("p", true, "t", t, "id", "1"))));
	}

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = { "Bearer not-a-jwt", "Bearer eyJhbGciOiJIUzI1NiJ9.e30.invalid-signature" })
	@DisplayName("잘못된 토큰을 보내면 공개 목록도 401이다")
	void invalidTokenIsRejectedOnList(String authorization) throws Exception {
		insertNotice("A", false, BASE);

		mockMvc.perform(get(NOTICES).header("Authorization", authorization))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
	}

	@Test
	@DisplayName("로그인·탈퇴 회원의 토큰이어도 공개 조회 응답은 같다(사용자별 필드가 없다)")
	void tokenDoesNotChangePublicResponse() throws Exception {
		long noticeId = insertNotice("A", true, BASE);
		String accessToken = signupAccessToken("user@example.com", "일반회원");
		String anonymous = mockMvc.perform(get(NOTICES)).andReturn().getResponse().getContentAsString();

		String loggedIn = mockMvc.perform(get(NOTICES).header("Authorization", "Bearer " + accessToken))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		jdbcTemplate.update("UPDATE users SET status = 'WITHDRAWN', withdrawn_at = now() WHERE nickname = '일반회원'");
		String withdrawn = mockMvc.perform(get(NOTICES).header("Authorization", "Bearer " + accessToken))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		mockMvc.perform(get(NOTICES + "/" + noticeId).header("Authorization", "Bearer " + accessToken))
				.andExpect(status().isOk());

		assertThat(loggedIn).isEqualTo(anonymous);
		assertThat(withdrawn).isEqualTo(anonymous);
	}

	@Test
	@DisplayName("목록은 조회 전용이다. 같은 경로의 POST는 인증이 필요하다")
	void listIsReadOnly() throws Exception {
		mockMvc.perform(post(NOTICES).contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
	}

	// --- 상세 ---

	@Test
	@DisplayName("상세는 모든 필드를 담고, 수정한 적 없으면 updatedAt이 null이다. isPinned로 나가고 pinned·authorId·deletedAt은 없다")
	void detail() throws Exception {
		String content = "3월 20일 02:00부터 04:00까지...\n<script>alert(1)</script> 원문 그대로";
		long noticeId = insertNotice("서비스 점검 안내", content, true, BASE);

		MvcResult result = mockMvc.perform(get(NOTICES + "/" + noticeId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.noticeId").value(noticeId))
				.andExpect(jsonPath("$.title").value("서비스 점검 안내"))
				.andExpect(jsonPath("$.content").value(content))
				.andExpect(jsonPath("$.isPinned").value(true))
				.andExpect(jsonPath("$.createdAt").value("2026-03-14T00:00:00Z"))
				.andReturn();

		JsonNode body = readBody(result);
		assertThat(keys(body)).containsExactlyInAnyOrder("noticeId", "title", "content", "isPinned", "createdAt",
				"updatedAt");
		assertThat(body.get("updatedAt").isNull()).isTrue();
	}

	@Test
	@DisplayName("수정된 공지의 updatedAt은 ISO 8601 문자열이다")
	void detailWithUpdatedAt() throws Exception {
		long noticeId = insertNotice("A", false, BASE);
		jdbcTemplate.update("UPDATE notices SET updated_at = ? WHERE notice_id = ?",
				OffsetDateTime.ofInstant(BASE.plus(1, ChronoUnit.DAYS), ZoneOffset.UTC), noticeId);

		mockMvc.perform(get(NOTICES + "/" + noticeId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.updatedAt").value("2026-03-15T00:00:00Z"));
	}

	@Test
	@DisplayName("삭제된 공지와 없는 공지는 구분 없이 404 NOT_FOUND다")
	void detailNotFound() throws Exception {
		long deleted = insertNotice("삭제", false, BASE);
		jdbcTemplate.update("UPDATE notices SET deleted_at = now() WHERE notice_id = ?", deleted);

		for (String id : List.of(String.valueOf(deleted), "999", "0", "-1")) {
			mockMvc.perform(get(NOTICES + "/" + id))
					.andExpect(status().isNotFound())
					.andExpect(jsonPath("$.code").value("NOT_FOUND"));
		}
	}

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = { "abc", "1.5", "99999999999999999999" })
	@DisplayName("noticeId가 Long이 아니면 400 INVALID_INPUT이다")
	void detailInvalidId(String id) throws Exception {
		mockMvc.perform(get(NOTICES + "/" + id))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"))
				.andExpect(jsonPath("$.message").value("noticeId: 값의 형식이 올바르지 않습니다."));
	}

	@Test
	@DisplayName("잘못된 토큰을 보내면 공개 상세도 401이다")
	void invalidTokenIsRejectedOnDetail() throws Exception {
		long noticeId = insertNotice("A", false, BASE);

		mockMvc.perform(get(NOTICES + "/" + noticeId).header("Authorization", "Bearer not-a-jwt"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
	}

	// --- helpers ---

	private long insertNotice(String title, boolean pinned, Instant createdAt) {
		return insertNotice(title, "본문", pinned, createdAt);
	}

	private long insertNotice(String title, String content, boolean pinned, Instant createdAt) {
		return jdbcTemplate.queryForObject("INSERT INTO notices (author_id, title, content, is_pinned, created_at) "
				+ "VALUES (?, ?, ?, ?, ?) RETURNING notice_id", Long.class, authorId, title, content, pinned,
				OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC));
	}

	/** 첫 페이지부터 nextCursor를 따라가며 페이지별 noticeId 목록을 모은다. 마지막 페이지는 hasNext=false, nextCursor=null */
	private List<List<Long>> collectPages(int size) throws Exception {
		List<List<Long>> pages = new ArrayList<>();
		String cursor = null;
		for (int guard = 0; guard < 20; guard++) {
			MockHttpServletRequestBuilder request = get(NOTICES).param("size", String.valueOf(size));
			if (cursor != null) {
				request.param("cursor", cursor);
			}
			JsonNode body = readBody(mockMvc.perform(request).andExpect(status().isOk()).andReturn());
			pages.add(ids(body));
			if (!body.get("hasNext").booleanValue()) {
				assertThat(body.get("nextCursor").isNull()).isTrue();
				return pages;
			}
			cursor = body.get("nextCursor").asString();
		}
		throw new AssertionError("페이지가 끝나지 않는다");
	}

	private String signupAccessToken(String email, String nickname) throws Exception {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("email", email);
		body.put("password", "hunter22!pw");
		body.put("nickname", nickname);
		body.put("termsOfServiceAgreed", true);
		body.put("privacyPolicyAgreed", true);
		MvcResult result = mockMvc.perform(post("/api/v1/auth/email/signup")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(body)))
				.andExpect(status().isCreated())
				.andReturn();
		return readBody(result).get("accessToken").asString();
	}

	private JsonNode readBody(MvcResult result) throws Exception {
		return objectMapper.readTree(result.getResponse().getContentAsString());
	}

	private static List<Long> ids(JsonNode page) {
		List<Long> ids = new ArrayList<>();
		page.get("items").forEach(item -> ids.add(item.get("noticeId").longValue()));
		return ids;
	}

	private static List<String> keys(JsonNode node) {
		return node.properties().stream().map(Map.Entry::getKey).toList();
	}

}
