package com.reused.notice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Stream;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.reused.TestcontainersConfiguration;
import com.reused.auth.client.OAuthProviderClient;
import com.reused.auth.mail.AuthMailSender;
import com.reused.auth.token.JwtTokenProvider;
import com.reused.user.entity.UserRole;

/**
 * 관리자 공지 등록·수정·삭제 통합 테스트. 알림 전체 발송과 감사 기록은 요청이 커밋된 뒤 DB에서 확인한다
 * (테스트 메서드에 {@code @Transactional}을 달지 않는다).
 *
 * <p>관리자 토큰은 가입 → role=ADMIN → 재발급으로 얻는다. 권한 거부(401·403)는 SecurityConfig와
 * AdminAccessInterceptor가 내며, 어떤 경우에도 공지·감사·알림 행이 생기거나 바뀌지 않아야 한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AdminNoticeIntegrationTest {

	private static final String ADMIN_NOTICES = "/api/v1/admin/notices";
	private static final String REFRESH_COOKIE = "refresh_token";
	private static final String ADMIN_EMAIL = "admin@example.com";
	private static final Instant CREATED_AT = Instant.parse("2026-03-14T00:00:00Z");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private StringRedisTemplate redisTemplate;

	@Autowired
	private JwtTokenProvider tokenProvider;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@MockitoBean
	private OAuthProviderClient kakaoOAuthClient;

	@MockitoBean
	private AuthMailSender mailSender;

	private String adminToken;
	private long adminId;

	@BeforeEach
	void resetState() throws Exception {
		jdbcTemplate.execute("TRUNCATE audit_logs, notifications, notices, notification_settings, "
				+ "user_status_histories, user_identities, users RESTART IDENTITY CASCADE");
		redisTemplate.execute((RedisCallback<Void>) connection -> {
			connection.serverCommands().flushDb();
			return null;
		});
		adminToken = adminToken(ADMIN_EMAIL, "관리자");
		adminId = userIdOf(ADMIN_EMAIL);
	}

	// --- 등록 ---

	@Test
	@DisplayName("관리자가 등록하면 201과 noticeId만 받고, 작성자·고정 여부가 저장되며 updated_at·deleted_at은 NULL이다")
	void createNotice() throws Exception {
		MvcResult result = mockMvc.perform(asAdmin(json(post(ADMIN_NOTICES),
						createBody("서비스 점검 안내", "3월 20일 02:00부터 04:00까지...", true))))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.noticeId").value(1))
				.andReturn();

		assertThat(keys(readBody(result))).containsExactly("noticeId");
		Map<String, Object> row = jdbcTemplate.queryForMap("SELECT author_id, title, content, is_pinned, created_at, "
				+ "updated_at, deleted_at FROM notices WHERE notice_id = 1");
		assertThat(row).containsEntry("author_id", adminId)
				.containsEntry("title", "서비스 점검 안내")
				.containsEntry("content", "3월 20일 02:00부터 04:00까지...")
				.containsEntry("is_pinned", true)
				.containsEntry("updated_at", null)
				.containsEntry("deleted_at", null);
		assertThat(row.get("created_at")).isNotNull();

		mockMvc.perform(get("/api/v1/notices/1"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.title").value("서비스 점검 안내"))
				.andExpect(jsonPath("$.isPinned").value(true));
	}

	@Test
	@DisplayName("등록하면 같은 트랜잭션에서 NOTICE_CREATE 감사 행이 남는다. detail에 제목·본문을 넣지 않는다")
	void createRecordsAudit() throws Exception {
		mockMvc.perform(asAdmin(json(post(ADMIN_NOTICES), createBody("서비스 점검 안내", "본문", false))))
				.andExpect(status().isCreated());

		List<Map<String, Object>> rows = noticeAuditRows();
		assertThat(rows).singleElement().satisfies(row -> assertThat(row)
				.containsEntry("actor_id", adminId)
				.containsEntry("action", "NOTICE_CREATE")
				.containsEntry("target_type", "NOTICE")
				.containsEntry("target_id", 1L)
				.containsEntry("result", "SUCCESS")
				.containsEntry("ip", "127.0.0.1")
				.containsEntry("detail", null));
	}

	@Test
	@DisplayName("isPinned를 생략하거나 null로 보내면 false로 저장한다")
	void isPinnedDefaultsToFalse() throws Exception {
		mockMvc.perform(asAdmin(json(post(ADMIN_NOTICES), Map.of("title", "생략", "content", "본문"))))
				.andExpect(status().isCreated());
		Map<String, Object> explicitNull = new LinkedHashMap<>(Map.of("title", "null", "content", "본문"));
		explicitNull.put("isPinned", null);
		mockMvc.perform(asAdmin(json(post(ADMIN_NOTICES), explicitNull)))
				.andExpect(status().isCreated());

		assertThat(jdbcTemplate.queryForList("SELECT is_pinned FROM notices ORDER BY notice_id", Boolean.class))
				.containsExactly(false, false);
	}

	@Test
	@DisplayName("제목 200자, 본문 5000자는 등록된다. 앞뒤 공백은 자르지 않는다")
	void createBoundaries() throws Exception {
		String title = "가".repeat(200);
		String content = " " + "나".repeat(4998) + " ";

		mockMvc.perform(asAdmin(json(post(ADMIN_NOTICES), createBody(title, content, false))))
				.andExpect(status().isCreated());

		Map<String, Object> row = jdbcTemplate.queryForMap("SELECT title, content FROM notices");
		assertThat(row).containsEntry("title", title).containsEntry("content", content);
	}

	@Test
	@DisplayName("등록 커밋 뒤 탈퇴자와 공지 알림을 끈 회원을 빼고 전원(관리자·정지 회원·설정 행 없는 회원 포함)에게 알림이 간다")
	void createBroadcastsNoticePublished() throws Exception {
		signup("user@example.com", "일반회원");
		long user = userIdOf("user@example.com");
		long noSettings = insertUser("설정없음", false);
		long noticeOff = insertUser("공지끔", true);
		jdbcTemplate.update("UPDATE notification_settings SET notice_enabled = false WHERE user_id = ?", noticeOff);
		long withdrawn = insertUser("탈퇴", true);
		jdbcTemplate.update("UPDATE users SET status = 'WITHDRAWN', withdrawn_at = now() WHERE user_id = ?", withdrawn);
		long suspended = insertUser("정지", true);
		jdbcTemplate.update("UPDATE users SET status = 'SUSPENDED' WHERE user_id = ?", suspended);

		mockMvc.perform(asAdmin(json(post(ADMIN_NOTICES), createBody("서비스 점검 안내", "본문은 알림에 없다", true))))
				.andExpect(status().isCreated());

		List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT user_id, type, title, body, target_type, "
				+ "target_id, read_at FROM notifications ORDER BY user_id");
		assertThat(rows).extracting(row -> row.get("user_id"))
				.containsExactly(adminId, user, noSettings, suspended);
		assertThat(rows).allSatisfy(row -> assertThat(row)
				.containsEntry("type", "NOTICE_PUBLISHED")
				.containsEntry("title", "공지사항이 등록되었습니다")
				.containsEntry("body", "서비스 점검 안내")
				.containsEntry("target_type", "NOTICE")
				.containsEntry("target_id", 1L)
				.containsEntry("read_at", null));
	}

	@Test
	@DisplayName("알림 발송이 실패해도 201이고 공지와 감사 행은 남는다(ADR-014)")
	void broadcastFailureKeepsNotice() throws Exception {
		jdbcTemplate.execute("ALTER TABLE notifications RENAME TO notifications_unavailable");
		try {
			mockMvc.perform(asAdmin(json(post(ADMIN_NOTICES), createBody("서비스 점검 안내", "본문", false))))
					.andExpect(status().isCreated())
					.andExpect(jsonPath("$.noticeId").value(1));
		}
		finally {
			jdbcTemplate.execute("ALTER TABLE notifications_unavailable RENAME TO notifications");
		}

		assertThat(countNotices()).isEqualTo(1);
		assertThat(noticeAuditRows()).hasSize(1);
		assertThat(countNotifications()).isZero();
	}

	@Test
	@DisplayName("감사 기록이 실패하면 공지 등록도 롤백되고 알림도 나가지 않는다")
	void auditFailureRollsBackCreate() throws Exception {
		jdbcTemplate.execute("ALTER TABLE audit_logs RENAME TO audit_logs_unavailable");
		try {
			mockMvc.perform(asAdmin(json(post(ADMIN_NOTICES), createBody("서비스 점검 안내", "본문", false))))
					.andExpect(status().isInternalServerError())
					.andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
		}
		finally {
			jdbcTemplate.execute("ALTER TABLE audit_logs_unavailable RENAME TO audit_logs");
		}

		assertThat(countNotices()).isZero();
		assertThat(countNotifications()).isZero();
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("invalidCreateBodies")
	@DisplayName("등록 요청 검증에 실패하면 400 INVALID_INPUT이고 공지·감사·알림이 생기지 않는다")
	void createValidation(String description, String body) throws Exception {
		mockMvc.perform(asAdmin(post(ADMIN_NOTICES).contentType(MediaType.APPLICATION_JSON).content(body)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"));

		assertNoWriteSideEffects();
	}

	static Stream<Arguments> invalidCreateBodies() {
		return Stream.of(
				Arguments.of("제목 누락", "{\"content\":\"본문\"}"),
				Arguments.of("제목 null", "{\"title\":null,\"content\":\"본문\"}"),
				Arguments.of("제목 빈 문자열", "{\"title\":\"\",\"content\":\"본문\"}"),
				Arguments.of("제목 공백뿐", "{\"title\":\"  \\t \",\"content\":\"본문\"}"),
				Arguments.of("제목 201자", "{\"title\":\"" + "가".repeat(201) + "\",\"content\":\"본문\"}"),
				Arguments.of("본문 누락", "{\"title\":\"제목\"}"),
				Arguments.of("본문 공백뿐", "{\"title\":\"제목\",\"content\":\" \\n \"}"),
				Arguments.of("본문 5001자", "{\"title\":\"제목\",\"content\":\"" + "나".repeat(5001) + "\"}"),
				Arguments.of("isPinned 타입 오류", "{\"title\":\"제목\",\"content\":\"본문\",\"isPinned\":\"x\"}"),
				Arguments.of("isPinned 객체", "{\"title\":\"제목\",\"content\":\"본문\",\"isPinned\":{}}"),
				Arguments.of("깨진 JSON", "{\"title\":\"제목\","),
				Arguments.of("JSON 배열", "[]"),
				Arguments.of("JSON null", "null"),
				Arguments.of("빈 본문", ""));
	}

	@Test
	@DisplayName("JSON이 아닌 Content-Type은 400이다")
	void createRequiresJson() throws Exception {
		mockMvc.perform(asAdmin(post(ADMIN_NOTICES).contentType(MediaType.TEXT_PLAIN).content("title=제목")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"));

		assertNoWriteSideEffects();
	}

	@Test
	@DisplayName("등록 검증 메시지는 필드 이름을 알려준다")
	void createValidationMessageNamesField() throws Exception {
		mockMvc.perform(asAdmin(json(post(ADMIN_NOTICES), Map.of("title", "가".repeat(201), "content", "본문"))))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(startsWith("title: ")));
	}

	// --- 수정 ---

	@Test
	@DisplayName("isPinned만 보내면 제목·본문은 그대로이고 updatedAt이 채워진 NoticeResponse를 준다. 알림은 늘지 않는다")
	void updatePinnedOnly() throws Exception {
		long noticeId = insertNotice("원래 제목", "원래 본문", false);

		MvcResult result = mockMvc.perform(asAdmin(json(patch(ADMIN_NOTICES + "/" + noticeId), Map.of("isPinned", true))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.noticeId").value(noticeId))
				.andExpect(jsonPath("$.title").value("원래 제목"))
				.andExpect(jsonPath("$.content").value("원래 본문"))
				.andExpect(jsonPath("$.isPinned").value(true))
				.andExpect(jsonPath("$.createdAt").value("2026-03-14T00:00:00Z"))
				.andReturn();

		JsonNode body = readBody(result);
		assertThat(keys(body)).containsExactlyInAnyOrder("noticeId", "title", "content", "isPinned", "createdAt",
				"updatedAt");
		assertThat(body.get("updatedAt").isString()).isTrue();
		// 응답의 updatedAt(메모리 값)과 이후 조회 값(DB 값)이 같다 — 마이크로초로 잘랐기 때문이다
		mockMvc.perform(get("/api/v1/notices/" + noticeId))
				.andExpect(jsonPath("$.updatedAt").value(body.get("updatedAt").asString()))
				.andExpect(jsonPath("$.isPinned").value(true));
		assertThat(countNotifications()).isZero();
	}

	@Test
	@DisplayName("수정하면 NOTICE_UPDATE 감사 행에 요청에 담긴 필드 이름만 남는다")
	void updateRecordsChangedFields() throws Exception {
		long noticeId = insertNotice("원래 제목", "원래 본문", false);

		mockMvc.perform(asAdmin(json(patch(ADMIN_NOTICES + "/" + noticeId), Map.of("isPinned", true))))
				.andExpect(status().isOk());
		mockMvc.perform(asAdmin(json(patch(ADMIN_NOTICES + "/" + noticeId),
						createBody("바뀐 제목", "바뀐 본문", false))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.title").value("바뀐 제목"))
				.andExpect(jsonPath("$.content").value("바뀐 본문"))
				.andExpect(jsonPath("$.isPinned").value(false));

		List<Map<String, Object>> rows = noticeAuditRows();
		assertThat(rows).hasSize(2).allSatisfy(row -> assertThat(row)
				.containsEntry("actor_id", adminId)
				.containsEntry("action", "NOTICE_UPDATE")
				.containsEntry("target_type", "NOTICE")
				.containsEntry("target_id", noticeId)
				.containsEntry("result", "SUCCESS")
				.containsEntry("ip", "127.0.0.1"));
		assertThat(jdbcTemplate.queryForList("SELECT detail->>'changedFields' FROM audit_logs "
				+ "WHERE action = 'NOTICE_UPDATE' ORDER BY audit_log_id", String.class))
				.containsExactly("[\"isPinned\"]", "[\"title\", \"content\", \"isPinned\"]");
		Map<String, Object> row = jdbcTemplate.queryForMap("SELECT title, content, is_pinned, updated_at, deleted_at "
				+ "FROM notices WHERE notice_id = ?", noticeId);
		assertThat(row).containsEntry("title", "바뀐 제목").containsEntry("content", "바뀐 본문")
				.containsEntry("is_pinned", false).containsEntry("deleted_at", null);
		assertThat(row.get("updated_at")).isNotNull();
	}

	@Test
	@DisplayName("값이 같아도 수정으로 보고 updatedAt을 갱신한다. 제목 200자·본문 5000자까지 허용한다")
	void updateSameValuesAndBoundaries() throws Exception {
		long noticeId = insertNotice("원래 제목", "원래 본문", false);

		mockMvc.perform(asAdmin(json(patch(ADMIN_NOTICES + "/" + noticeId), Map.of("title", "원래 제목"))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.updatedAt").isNotEmpty());
		mockMvc.perform(asAdmin(json(patch(ADMIN_NOTICES + "/" + noticeId),
						Map.of("title", "가".repeat(200), "content", "나".repeat(5000)))))
				.andExpect(status().isOk());

		assertThat(jdbcTemplate.queryForObject("SELECT char_length(title) + char_length(content) FROM notices",
				Integer.class)).isEqualTo(5200);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("invalidUpdateBodies")
	@DisplayName("수정 요청 검증에 실패하면 400 INVALID_INPUT이고 공지는 그대로이며 감사 행이 없다")
	void updateValidation(String description, String body) throws Exception {
		long noticeId = insertNotice("원래 제목", "원래 본문", false);

		mockMvc.perform(asAdmin(patch(ADMIN_NOTICES + "/" + noticeId).contentType(MediaType.APPLICATION_JSON)
						.content(body)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"));

		assertNoticeUntouched(noticeId);
	}

	static Stream<Arguments> invalidUpdateBodies() {
		return Stream.of(
				Arguments.of("빈 객체", "{}"),
				Arguments.of("모두 null", "{\"title\":null,\"content\":null,\"isPinned\":null}"),
				Arguments.of("모르는 필드만", "{\"pinned\":true}"),
				Arguments.of("제목 빈 문자열", "{\"title\":\"\"}"),
				Arguments.of("제목 공백뿐", "{\"title\":\"  \\t \"}"),
				Arguments.of("본문 공백뿐", "{\"content\":\" \\n \"}"),
				Arguments.of("제목 201자", "{\"title\":\"" + "가".repeat(201) + "\"}"),
				Arguments.of("본문 5001자", "{\"content\":\"" + "나".repeat(5001) + "\"}"),
				Arguments.of("유효한 필드와 공백 제목", "{\"title\":\" \",\"isPinned\":true}"),
				Arguments.of("isPinned 타입 오류", "{\"isPinned\":\"x\"}"),
				Arguments.of("깨진 JSON", "{\"title\":"),
				Arguments.of("JSON 배열", "[]"),
				Arguments.of("빈 본문", ""));
	}

	@Test
	@DisplayName("빈 수정 요청은 '변경할 항목이 없습니다.'이고, 없는 공지여도 입력 검증이 먼저다")
	void emptyUpdateMessage() throws Exception {
		long noticeId = insertNotice("원래 제목", "원래 본문", false);

		for (long id : new long[] { noticeId, 999L }) {
			mockMvc.perform(asAdmin(json(patch(ADMIN_NOTICES + "/" + id), Map.of())))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.message").value("변경할 항목이 없습니다."));
		}
	}

	@Test
	@DisplayName("공백뿐인 제목의 메시지는 필드 이름을 알려준다")
	void blankTitleMessage() throws Exception {
		long noticeId = insertNotice("원래 제목", "원래 본문", false);

		mockMvc.perform(asAdmin(json(patch(ADMIN_NOTICES + "/" + noticeId), Map.of("title", "   "))))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("title: 공백만 입력할 수 없습니다."));
	}

	@Test
	@DisplayName("없는 공지와 삭제된 공지의 수정은 404이고 삭제된 공지는 되살아나지 않는다")
	void updateNotFound() throws Exception {
		long deleted = insertNotice("삭제됨", "본문", true);
		jdbcTemplate.update("UPDATE notices SET deleted_at = now() WHERE notice_id = ?", deleted);

		for (long id : new long[] { deleted, 999L }) {
			mockMvc.perform(asAdmin(json(patch(ADMIN_NOTICES + "/" + id), Map.of("title", "되살리기"))))
					.andExpect(status().isNotFound())
					.andExpect(jsonPath("$.code").value("NOT_FOUND"));
		}

		Map<String, Object> row = jdbcTemplate.queryForMap("SELECT title, updated_at, deleted_at FROM notices "
				+ "WHERE notice_id = ?", deleted);
		assertThat(row).containsEntry("title", "삭제됨").containsEntry("updated_at", null);
		assertThat(row.get("deleted_at")).isNotNull();
		assertThat(noticeAuditRows()).isEmpty();
	}

	@Test
	@DisplayName("삭제 트랜잭션과 겹친 수정은 삭제 커밋을 기다린 뒤 404가 되고 삭제된 공지를 되살리지 않는다")
	void concurrentUpdateDoesNotResurrectDeletedNotice() throws Exception {
		long noticeId = insertNotice("원래 제목", "원래 본문", false);
		TransactionTemplate tx = new TransactionTemplate(transactionManager);
		CountDownLatch deletedButNotCommitted = new CountDownLatch(1);
		CountDownLatch commit = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<?> deleter = executor.submit(() -> tx.executeWithoutResult(status -> {
				jdbcTemplate.update("UPDATE notices SET deleted_at = now() WHERE notice_id = ?", noticeId);
				deletedButNotCommitted.countDown();
				awaitLatch(commit);
			}));
			assertThat(deletedButNotCommitted.await(10, TimeUnit.SECONDS)).isTrue();

			Future<MvcResult> patcher = executor.submit(() -> mockMvc.perform(asAdmin(
					json(patch(ADMIN_NOTICES + "/" + noticeId), Map.of("title", "되살리기")))).andReturn());
			awaitLockWait();
			commit.countDown();
			deleter.get(10, TimeUnit.SECONDS);

			MvcResult result = patcher.get(10, TimeUnit.SECONDS);
			assertThat(result.getResponse().getStatus()).isEqualTo(404);
		}
		finally {
			commit.countDown();
			executor.shutdownNow();
		}

		Map<String, Object> row = jdbcTemplate.queryForMap("SELECT title, updated_at, deleted_at FROM notices "
				+ "WHERE notice_id = ?", noticeId);
		assertThat(row).containsEntry("title", "원래 제목").containsEntry("updated_at", null);
		assertThat(row.get("deleted_at")).isNotNull();
		assertThat(noticeAuditRows()).isEmpty();
	}

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = { "abc", "99999999999999999999" })
	@DisplayName("수정·삭제의 noticeId가 Long이 아니면 400이다")
	void invalidNoticeId(String id) throws Exception {
		mockMvc.perform(asAdmin(json(patch(ADMIN_NOTICES + "/" + id), Map.of("isPinned", true))))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"));
		mockMvc.perform(asAdmin(delete(ADMIN_NOTICES + "/" + id)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"));
	}

	// --- 삭제 ---

	@Test
	@DisplayName("삭제는 204(본문 없음)이고 행은 남은 채 deleted_at만 채워진다. updated_at·is_pinned는 그대로다")
	void deleteNotice() throws Exception {
		long noticeId = insertNotice("삭제할 공지", "본문", true);

		MvcResult result = mockMvc.perform(asAdmin(delete(ADMIN_NOTICES + "/" + noticeId)))
				.andExpect(status().isNoContent())
				.andReturn();

		assertThat(result.getResponse().getContentAsString()).isEmpty();
		Map<String, Object> row = jdbcTemplate.queryForMap("SELECT title, is_pinned, updated_at, deleted_at "
				+ "FROM notices WHERE notice_id = ?", noticeId);
		assertThat(row).containsEntry("title", "삭제할 공지").containsEntry("is_pinned", true)
				.containsEntry("updated_at", null);
		assertThat(row.get("deleted_at")).isNotNull();
		assertThat(noticeAuditRows()).singleElement().satisfies(audit -> assertThat(audit)
				.containsEntry("actor_id", adminId)
				.containsEntry("action", "NOTICE_DELETE")
				.containsEntry("target_type", "NOTICE")
				.containsEntry("target_id", noticeId)
				.containsEntry("result", "SUCCESS")
				.containsEntry("ip", "127.0.0.1")
				.containsEntry("detail", null));
	}

	@Test
	@DisplayName("삭제한 공지는 상세 404, 목록에서 빠진다. 다시 삭제하면 404이고 감사 행은 한 건뿐이다")
	void deletedNoticeDisappears() throws Exception {
		long noticeId = insertNotice("삭제할 공지", "본문", true);
		long kept = insertNotice("남는 공지", "본문", false);

		mockMvc.perform(asAdmin(delete(ADMIN_NOTICES + "/" + noticeId))).andExpect(status().isNoContent());

		mockMvc.perform(get("/api/v1/notices/" + noticeId))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_FOUND"));
		mockMvc.perform(get("/api/v1/notices"))
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].noticeId").value(kept));
		mockMvc.perform(asAdmin(delete(ADMIN_NOTICES + "/" + noticeId)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_FOUND"));
		assertThat(noticeAuditRows()).hasSize(1);
	}

	@Test
	@DisplayName("없는 공지를 삭제하면 404다")
	void deleteNotFound() throws Exception {
		mockMvc.perform(asAdmin(delete(ADMIN_NOTICES + "/999")))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_FOUND"));

		assertThat(noticeAuditRows()).isEmpty();
	}

	@Test
	@DisplayName("삭제해도 이미 보낸 NOTICE_PUBLISHED 알림은 지우지 않는다")
	void deleteKeepsSentNotifications() throws Exception {
		signup("user@example.com", "일반회원");
		mockMvc.perform(asAdmin(json(post(ADMIN_NOTICES), createBody("점검 안내", "본문", false))))
				.andExpect(status().isCreated());
		assertThat(countNotifications()).isEqualTo(2);

		mockMvc.perform(asAdmin(delete(ADMIN_NOTICES + "/1"))).andExpect(status().isNoContent());

		assertThat(countNotifications()).isEqualTo(2);
	}

	@Test
	@DisplayName("감사 기록이 실패하면 삭제도 롤백된다")
	void auditFailureRollsBackDelete() throws Exception {
		long noticeId = insertNotice("삭제할 공지", "본문", false);
		jdbcTemplate.execute("ALTER TABLE audit_logs RENAME TO audit_logs_unavailable");
		try {
			mockMvc.perform(asAdmin(delete(ADMIN_NOTICES + "/" + noticeId)))
					.andExpect(status().isInternalServerError());
		}
		finally {
			jdbcTemplate.execute("ALTER TABLE audit_logs_unavailable RENAME TO audit_logs");
		}

		assertThat(jdbcTemplate.queryForObject("SELECT deleted_at FROM notices WHERE notice_id = ?",
				OffsetDateTime.class, noticeId)).isNull();
	}

	// --- 권한 (세 엔드포인트 공통) ---

	@ParameterizedTest(name = "{0}")
	@MethodSource("writeEndpoints")
	@DisplayName("토큰이 없거나 잘못되면 401 UNAUTHENTICATED이고 아무것도 바뀌지 않는다")
	void writeRequiresAuthentication(Function<Long, MockHttpServletRequestBuilder> endpoint) throws Exception {
		long noticeId = insertNotice("원래 제목", "원래 본문", false);

		mockMvc.perform(endpoint.apply(noticeId))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
		mockMvc.perform(endpoint.apply(noticeId).header("Authorization", "Bearer not-a-jwt"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

		assertNoticeUntouched(noticeId);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("writeEndpoints")
	@DisplayName("USER 토큰은 403 FORBIDDEN이고 아무것도 바뀌지 않는다")
	void writeForbiddenForUser(Function<Long, MockHttpServletRequestBuilder> endpoint) throws Exception {
		long noticeId = insertNotice("원래 제목", "원래 본문", false);
		String userToken = signup("user@example.com", "일반회원").accessToken();

		mockMvc.perform(endpoint.apply(noticeId).header("Authorization", "Bearer " + userToken))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("FORBIDDEN"));

		assertNoticeUntouched(noticeId);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("writeEndpoints")
	@DisplayName("ADMIN 토큰이 남아 있어도 DB에서 역할 회수·정지(무기한·기간 남음)면 403이고 아무것도 바뀌지 않는다")
	void writeForbiddenWhenDbStateIsNotActiveAdmin(Function<Long, MockHttpServletRequestBuilder> endpoint)
			throws Exception {
		long noticeId = insertNotice("원래 제목", "원래 본문", false);
		List<String> states = List.of(
				"role = 'USER'",
				"status = 'SUSPENDED', suspended_until = NULL",
				"status = 'SUSPENDED', suspended_until = now() + interval '1 day'");

		for (String state : states) {
			jdbcTemplate.update("UPDATE users SET role = 'ADMIN', status = 'ACTIVE', suspended_until = NULL, "
					+ "withdrawn_at = NULL WHERE user_id = ?", adminId);
			jdbcTemplate.update("UPDATE users SET " + state + " WHERE user_id = ?", adminId);

			mockMvc.perform(asAdmin(endpoint.apply(noticeId)))
					.andExpect(status().isForbidden())
					.andExpect(jsonPath("$.code").value("FORBIDDEN"));
		}

		assertNoticeUntouched(noticeId);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("writeEndpoints")
	@DisplayName("탈퇴한 관리자의 남은 ADMIN 토큰은 401 UNAUTHENTICATED이고 아무것도 바뀌지 않는다(탈퇴 토큰 규칙)")
	void writeUnauthenticatedForWithdrawnAdmin(Function<Long, MockHttpServletRequestBuilder> endpoint)
			throws Exception {
		long noticeId = insertNotice("원래 제목", "원래 본문", false);
		jdbcTemplate.update("UPDATE users SET status = 'WITHDRAWN', withdrawn_at = now() WHERE user_id = ?", adminId);

		mockMvc.perform(asAdmin(endpoint.apply(noticeId)))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

		assertNoticeUntouched(noticeId);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("writeEndpoints")
	@DisplayName("DB에 없는 사용자의 ADMIN 토큰은 401이다")
	void writeUnauthenticatedForUnknownUser(Function<Long, MockHttpServletRequestBuilder> endpoint) throws Exception {
		long noticeId = insertNotice("원래 제목", "원래 본문", false);
		String forged = tokenProvider.issueAccessToken(999L, UserRole.ADMIN);

		mockMvc.perform(endpoint.apply(noticeId).header("Authorization", "Bearer " + forged))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

		assertNoticeUntouched(noticeId);
	}

	@Test
	@DisplayName("정지 기간이 이미 지난 관리자는 정지로 보지 않아 등록·수정·삭제할 수 있다")
	void expiredSuspensionAdminCanWrite() throws Exception {
		jdbcTemplate.update("UPDATE users SET status = 'SUSPENDED', suspended_until = now() - interval '1 minute' "
				+ "WHERE user_id = ?", adminId);

		mockMvc.perform(asAdmin(json(post(ADMIN_NOTICES), createBody("제목", "본문", false))))
				.andExpect(status().isCreated());
		mockMvc.perform(asAdmin(json(patch(ADMIN_NOTICES + "/1"), Map.of("isPinned", true))))
				.andExpect(status().isOk());
		mockMvc.perform(asAdmin(delete(ADMIN_NOTICES + "/1")))
				.andExpect(status().isNoContent());
	}

	static Stream<Arguments> writeEndpoints() {
		return Stream.of(
				Arguments.of(Named.<Function<Long, MockHttpServletRequestBuilder>>of("POST", id -> post(ADMIN_NOTICES)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"title\":\"새 공지\",\"content\":\"본문\",\"isPinned\":true}"))),
				Arguments.of(Named.<Function<Long, MockHttpServletRequestBuilder>>of("PATCH",
						id -> patch(ADMIN_NOTICES + "/" + id)
								.contentType(MediaType.APPLICATION_JSON)
								.content("{\"title\":\"바뀐 제목\",\"isPinned\":true}"))),
				Arguments.of(Named.<Function<Long, MockHttpServletRequestBuilder>>of("DELETE",
						id -> delete(ADMIN_NOTICES + "/" + id))));
	}

	// --- helpers ---

	/** 다른 세션이 행 잠금을 기다리는 상태가 될 때까지 기다린다(pg_stat_activity) */
	private void awaitLockWait() throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (System.nanoTime() < deadline) {
			Long waiting = jdbcTemplate.queryForObject("SELECT count(*) FROM pg_stat_activity "
					+ "WHERE datname = current_database() AND wait_event_type = 'Lock'", Long.class);
			if (waiting != null && waiting > 0) {
				return;
			}
			Thread.sleep(20);
		}
		throw new AssertionError("수정 요청이 행 잠금을 기다리지 않았다");
	}

	private static void awaitLatch(CountDownLatch latch) {
		try {
			if (!latch.await(10, TimeUnit.SECONDS)) {
				throw new IllegalStateException("latch timeout");
			}
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
	}

	/** 공지 1건만 있고 원래 값 그대로이며, 공지 감사·알림 행이 없다 */
	private void assertNoticeUntouched(long noticeId) {
		assertThat(countNotices()).isEqualTo(1);
		Map<String, Object> row = jdbcTemplate.queryForMap("SELECT title, content, is_pinned, updated_at, deleted_at "
				+ "FROM notices WHERE notice_id = ?", noticeId);
		assertThat(row).containsEntry("title", "원래 제목").containsEntry("content", "원래 본문")
				.containsEntry("is_pinned", false).containsEntry("updated_at", null).containsEntry("deleted_at", null);
		assertThat(noticeAuditRows()).isEmpty();
		assertThat(countNotifications()).isZero();
	}

	private void assertNoWriteSideEffects() {
		assertThat(countNotices()).isZero();
		assertThat(noticeAuditRows()).isEmpty();
		assertThat(countNotifications()).isZero();
	}

	private List<Map<String, Object>> noticeAuditRows() {
		return jdbcTemplate.queryForList("SELECT actor_id, action, target_type, target_id, result, "
				+ "host(ip_address) AS ip, detail::text AS detail FROM audit_logs WHERE action LIKE 'NOTICE\\_%' "
				+ "ORDER BY audit_log_id");
	}

	private long countNotices() {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM notices", Long.class);
	}

	private long countNotifications() {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM notifications", Long.class);
	}

	private long insertNotice(String title, String content, boolean pinned) {
		return jdbcTemplate.queryForObject("INSERT INTO notices (author_id, title, content, is_pinned, created_at) "
				+ "VALUES (?, ?, ?, ?, ?) RETURNING notice_id", Long.class, adminId, title, content, pinned,
				OffsetDateTime.ofInstant(CREATED_AT, ZoneOffset.UTC));
	}

	private long insertUser(String nickname, boolean withSettings) {
		Long userId = jdbcTemplate.queryForObject(
				"INSERT INTO users (nickname, terms_agreed_at) VALUES (?, now()) RETURNING user_id", Long.class,
				nickname);
		if (withSettings) {
			jdbcTemplate.update("INSERT INTO notification_settings (user_id) VALUES (?)", userId);
		}
		return userId;
	}

	private long userIdOf(String email) {
		return jdbcTemplate.queryForObject("SELECT user_id FROM user_identities WHERE provider = 'LOCAL' AND email = ?",
				Long.class, email);
	}

	private static Map<String, Object> createBody(String title, String content, boolean pinned) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("title", title);
		body.put("content", content);
		body.put("isPinned", pinned);
		return body;
	}

	private MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder builder) {
		return builder.header("Authorization", "Bearer " + adminToken);
	}

	private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder, Map<String, ?> body) {
		return builder.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
	}

	private Tokens signup(String email, String nickname) throws Exception {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("email", email);
		body.put("password", "hunter22!pw");
		body.put("nickname", nickname);
		body.put("termsOfServiceAgreed", true);
		body.put("privacyPolicyAgreed", true);
		MvcResult result = mockMvc.perform(json(post("/api/v1/auth/email/signup"), body))
				.andExpect(status().isCreated())
				.andReturn();
		return new Tokens(readBody(result).get("accessToken").asString(),
				result.getResponse().getCookie(REFRESH_COOKIE).getValue());
	}

	/** 가입 → role=ADMIN → 재발급. AuthService.refresh는 DB의 role로 새 Access Token을 만든다. */
	private String adminToken(String email, String nickname) throws Exception {
		Tokens tokens = signup(email, nickname);
		jdbcTemplate.update("UPDATE users SET role = 'ADMIN' WHERE user_id = "
				+ "(SELECT user_id FROM user_identities WHERE provider = 'LOCAL' AND email = ?)", email);
		MvcResult refreshed = mockMvc.perform(post("/api/v1/auth/refresh")
						.cookie(new Cookie(REFRESH_COOKIE, tokens.refreshToken())))
				.andExpect(status().isOk())
				.andReturn();
		return readBody(refreshed).get("accessToken").asString();
	}

	private JsonNode readBody(MvcResult result) throws Exception {
		return objectMapper.readTree(result.getResponse().getContentAsString());
	}

	private static List<String> keys(JsonNode node) {
		return node.properties().stream().map(Map.Entry::getKey).toList();
	}

	private record Tokens(String accessToken, String refreshToken) {
	}

}
