package com.reused.user.admin;

import static com.reused.support.AdminTestClient.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.reused.TestcontainersConfiguration;
import com.reused.admin.api.ListingStatsPort;
import com.reused.admin.api.MarketplaceMetricsPort;
import com.reused.auth.client.OAuthProviderClient;
import com.reused.auth.mail.AuthMailSender;
import com.reused.common.pagination.CursorCodec;
import com.reused.support.AdminTestClient;
import com.reused.support.AdminTestClient.Member;

/**
 * GET /api/v1/admin/users. 관리자 권한 규칙(401·403)은 {@code AdminEndpointAccessIntegrationTest}가 다섯 엔드포인트를 함께 본다.
 * 게시글 수는 A의 포트를 대역으로 바꿔 전달만 확인한다. 포트가 없을 때는 {@code MarketplacePortsAbsentIntegrationTest}.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AdminUserListIntegrationTest {

	private static final String PATH = "/api/v1/admin/users";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private StringRedisTemplate redisTemplate;

	@MockitoBean
	private AuthMailSender mailSender;

	@MockitoBean
	private OAuthProviderClient kakaoOAuthClient;

	@MockitoBean
	private ListingStatsPort listingStatsPort;

	@MockitoBean
	private MarketplaceMetricsPort marketplaceMetricsPort;

	private AdminTestClient client;
	private Member admin;

	@BeforeEach
	void resetState() throws Exception {
		client = new AdminTestClient(mockMvc, objectMapper, jdbcTemplate, redisTemplate);
		client.reset();
		admin = client.signupAdmin("admin@example.com", "관리자");
		// 가입이 남긴 AUTH_SIGNUP은 이 테스트와 무관하다.
		jdbcTemplate.update("DELETE FROM audit_logs");
	}

	@Test
	@DisplayName("가입 최신순으로 역할·상태·게시글 수·피신고 수를 주고 개인정보 필드는 싣지 않는다")
	void listsUsersNewestFirst() throws Exception {
		long jaehyun = client.insertUser("재현");
		long minji = client.insertUser("민지");
		client.insertReport(admin.userId(), "USER", jaehyun, "OTHER", "RECEIVED");
		client.insertReport(minji, "USER", jaehyun, "FRAUD_SUSPICION", "RESOLVED");
		client.insertReport(admin.userId(), "USER", jaehyun, "NO_SHOW", "REJECTED");
		// 게시글 신고는 회원 피신고 수에 들어가지 않는다.
		client.insertReport(admin.userId(), "LISTING", jaehyun, "SPAM", "RECEIVED");
		given(listingStatsPort.countBySellers(anyCollection())).willReturn(Map.of(jaehyun, 5L));

		String body = list()
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(3))
				.andExpect(jsonPath("$.items[0].userId").value(minji))
				.andExpect(jsonPath("$.items[1].userId").value(jaehyun))
				.andExpect(jsonPath("$.items[1].nickname").value("재현"))
				.andExpect(jsonPath("$.items[1].role").value("USER"))
				.andExpect(jsonPath("$.items[1].status").value("ACTIVE"))
				.andExpect(jsonPath("$.items[1].suspendedUntil").value(nullValue()))
				.andExpect(jsonPath("$.items[1].listingCount").value(5))
				.andExpect(jsonPath("$.items[1].reportedCount").value(3))
				.andExpect(jsonPath("$.items[1].createdAt").isString())
				.andExpect(jsonPath("$.items[0].listingCount").value(0))
				.andExpect(jsonPath("$.items[0].reportedCount").value(0))
				.andExpect(jsonPath("$.items[2].userId").value(admin.userId()))
				.andExpect(jsonPath("$.items[2].role").value("ADMIN"))
				.andExpect(jsonPath("$.nextCursor").value(nullValue()))
				.andExpect(jsonPath("$.hasNext").value(false))
				.andReturn().getResponse().getContentAsString();

		JsonNode item = objectMapper.readTree(body).get("items").get(1);
		assertThat(fieldNames(item)).containsExactlyInAnyOrder("userId", "nickname", "role", "status",
				"suspendedUntil", "listingCount", "reportedCount", "createdAt");
		assertThat(Instant.parse(item.get("createdAt").asString()))
				.isCloseTo(Instant.now(), within(1, ChronoUnit.MINUTES));
	}

	@Test
	@DisplayName("게시글 수는 한 페이지의 id 목록으로 한 번만 읽는다(N+1 없음)")
	@SuppressWarnings("unchecked")
	void readsListingCountsOncePerPage() throws Exception {
		long first = client.insertUser("첫째");
		long second = client.insertUser("둘째");

		list().andExpect(status().isOk());

		ArgumentCaptor<Collection<Long>> ids = ArgumentCaptor.forClass(Collection.class);
		verify(listingStatsPort, times(1)).countBySellers(ids.capture());
		assertThat(ids.getValue()).containsExactlyInAnyOrder(second, first, admin.userId());
	}

	@Test
	@DisplayName("포트가 일부 회원의 값을 주지 않으면 그 회원의 게시글 수는 0이다")
	void missingListingCountIsZero() throws Exception {
		long seller = client.insertUser("판매자");
		given(listingStatsPort.countBySellers(anyCollection())).willReturn(Map.of(seller, 2L));

		list()
				.andExpect(jsonPath("$.items[0].listingCount").value(2))
				.andExpect(jsonPath("$.items[1].listingCount").value(0));
	}

	@Test
	@DisplayName("상태 필터: SUSPENDED는 정지 회원만, 종료 시각을 싣는다. 무기한이면 null이다")
	void filtersSuspended() throws Exception {
		client.insertUser("활성회원");
		long timed = client.insertUser("기간정지");
		long indefinite = client.insertUser("무기한정지");
		Instant until = Instant.now().plus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
		jdbcTemplate.update("UPDATE users SET status = 'SUSPENDED', suspended_until = ? WHERE user_id = ?",
				Timestamp.from(until), timed);
		client.suspend(indefinite, "NULL");

		list("status", "SUSPENDED")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(2))
				.andExpect(jsonPath("$.items[0].userId").value(indefinite))
				.andExpect(jsonPath("$.items[0].status").value("SUSPENDED"))
				.andExpect(jsonPath("$.items[0].suspendedUntil").value(nullValue()))
				.andExpect(jsonPath("$.items[1].userId").value(timed))
				.andExpect(jsonPath("$.items[1].suspendedUntil").value(until.toString()));
	}

	@Test
	@DisplayName("상태 필터: WITHDRAWN은 탈퇴 회원만 닉네임 그대로 주고, 정지 중 탈퇴해 남은 종료 시각은 싣지 않는다")
	void filtersWithdrawn() throws Exception {
		long active = client.insertUser("활성회원");
		long withdrawn = client.insertUser("탈퇴예정");
		jdbcTemplate.update("UPDATE users SET status = 'SUSPENDED', suspended_until = now() + interval '3 days' "
				+ "WHERE user_id = ?", withdrawn);
		client.withdraw(withdrawn);
		// 탈퇴 시각만 기록된 비정상 행도 탈퇴로 분류한다(User.isWithdrawn과 같은 기준).
		long withdrawnAtOnly = client.insertUser("시각만기록");
		jdbcTemplate.update("UPDATE users SET withdrawn_at = now() WHERE user_id = ?", withdrawnAtOnly);

		list("status", "WITHDRAWN")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(2))
				.andExpect(jsonPath("$.items[0].userId").value(withdrawnAtOnly))
				.andExpect(jsonPath("$.items[0].status").value("WITHDRAWN"))
				.andExpect(jsonPath("$.items[1].userId").value(withdrawn))
				.andExpect(jsonPath("$.items[1].nickname").value("탈퇴회원#" + withdrawn))
				.andExpect(jsonPath("$.items[1].status").value("WITHDRAWN"))
				.andExpect(jsonPath("$.items[1].suspendedUntil").value(nullValue()));

		list("status", "ACTIVE")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(2))
				.andExpect(jsonPath("$.items[0].userId").value(active))
				.andExpect(jsonPath("$.items[1].userId").value(admin.userId()));
	}

	@Test
	@DisplayName("상태 값이 enum이 아니면 400이고 내부 타입 이름을 싣지 않는다")
	void invalidStatusIsRejected() throws Exception {
		list("status", "FOO")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"))
				.andExpect(jsonPath("$.message").value("status: 값의 형식이 올바르지 않습니다."));
	}

	@Test
	@DisplayName("닉네임 검색은 대소문자를 무시한 부분 일치이고 앞뒤 공백을 뗀다. 공백뿐이면 적용하지 않는다")
	void keywordSearch() throws Exception {
		long upper = client.insertUser("Kim철수");
		long lower = client.insertUser("kim영희");
		long park = client.insertUser("박민수");

		list("keyword", "KIM")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(2))
				.andExpect(jsonPath("$.items[0].userId").value(lower))
				.andExpect(jsonPath("$.items[1].userId").value(upper));
		list("keyword", "  민수 ")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].userId").value(park));
		list("keyword", "   ")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(4));
		list("keyword", "없는닉네임")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(0))
				.andExpect(jsonPath("$.hasNext").value(false));
	}

	@Test
	@DisplayName("검색어의 %, _, \\는 와일드카드가 아니라 글자 그대로 찾는다")
	void keywordEscapesLikeWildcards() throws Exception {
		long percent = client.insertUser("100%달성");
		long underscore = client.insertUser("a_b회원");
		client.insertUser("ab회원");
		long backslash = client.insertUser("역\\슬래시");

		list("keyword", "%")
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].userId").value(percent));
		list("keyword", "_")
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].userId").value(underscore));
		list("keyword", "\\")
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].userId").value(backslash));
	}

	@Test
	@DisplayName("검색어가 닉네임 최대 길이(20자)를 넘으면 400이다")
	void keywordTooLongIsRejected() throws Exception {
		list("keyword", "가".repeat(20)).andExpect(status().isOk());
		list("keyword", "가".repeat(21))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"));
	}

	@Test
	@DisplayName("size를 생략하면 20건이고 nextCursor로 다음 페이지를 이어 읽는다. 마지막 페이지는 nextCursor=null")
	void defaultPageSizeAndNextPage() throws Exception {
		for (int i = 1; i <= 25; i++) {
			client.insertUser("회원" + i);
		}
		// 관리자(1) + 25명 = 26명. 첫 페이지는 26..7, 다음 페이지는 6..1

		String firstPage = list()
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(20))
				.andExpect(jsonPath("$.items[0].userId").value(26))
				.andExpect(jsonPath("$.items[19].userId").value(7))
				.andExpect(jsonPath("$.hasNext").value(true))
				.andExpect(jsonPath("$.nextCursor").value(CursorCodec.encodeId(7)))
				.andReturn().getResponse().getContentAsString();
		String nextCursor = objectMapper.readTree(firstPage).get("nextCursor").asString();

		list("cursor", nextCursor)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(6))
				.andExpect(jsonPath("$.items[0].userId").value(6))
				.andExpect(jsonPath("$.items[5].userId").value(1))
				.andExpect(jsonPath("$.hasNext").value(false))
				.andExpect(jsonPath("$.nextCursor").value(nullValue()));
	}

	@Test
	@DisplayName("size 경계: 1과 100은 받고, 남은 건수와 크기가 같으면 다음 페이지가 없다")
	void sizeBounds() throws Exception {
		client.insertUser("둘째");

		list("size", "1")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].userId").value(2))
				.andExpect(jsonPath("$.hasNext").value(true))
				.andExpect(jsonPath("$.nextCursor").value(CursorCodec.encodeId(2)));
		list("size", "2")
				.andExpect(jsonPath("$.items.length()").value(2))
				.andExpect(jsonPath("$.hasNext").value(false))
				.andExpect(jsonPath("$.nextCursor").value(nullValue()));
		list("size", "100")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(2));
	}

	@ParameterizedTest(name = "size={0}")
	@ValueSource(strings = { "0", "-1", "101", "abc" })
	@DisplayName("size가 1~100 밖이거나 숫자가 아니면 자르지 않고 400이다")
	void sizeOutOfRangeIsRejected(String size) throws Exception {
		list("size", size)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"));
	}

	@ParameterizedTest(name = "cursor={0}")
	@ValueSource(strings = { "not-a-cursor", "eyJpZCI6ImFiYyJ9", "eyJpZCI6MH0", "W10" })
	@DisplayName("해석할 수 없는 커서는 400이다(깨진 값, id가 문자열, id가 0, 객체가 아닌 JSON)")
	void invalidCursorIsRejected(String cursor) throws Exception {
		list("cursor", cursor)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"))
				.andExpect(jsonPath("$.message").value("커서가 올바르지 않습니다."));
	}

	@Test
	@DisplayName("문서 예시 형식의 표준 Base64 커서(eyJpZCI6MTIzfQ==, {\"id\":123})도 받는다")
	void acceptsDocumentStyleCursor() throws Exception {
		list("cursor", "eyJpZCI6MTIzfQ==")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(1));
	}

	@Test
	@DisplayName("조건과 커서를 함께 쓰면 조건 안에서 다음 페이지를 읽는다")
	void filterWithCursor() throws Exception {
		long first = client.insertUser("검색1");
		client.insertUser("다른회원");
		long second = client.insertUser("검색2");
		long third = client.insertUser("검색3");

		String page = list("keyword", "검색", "size", "2")
				.andExpect(jsonPath("$.items[0].userId").value(third))
				.andExpect(jsonPath("$.items[1].userId").value(second))
				.andExpect(jsonPath("$.hasNext").value(true))
				.andReturn().getResponse().getContentAsString();

		list("keyword", "검색", "size", "2", "cursor", objectMapper.readTree(page).get("nextCursor").asString())
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].userId").value(first))
				.andExpect(jsonPath("$.hasNext").value(false));
	}

	@Test
	@DisplayName("목록 조회는 읽기 전용이다. 감사 로그나 이력을 남기지 않는다")
	void listHasNoSideEffects() throws Exception {
		client.insertUser("회원");

		list("status", "ACTIVE", "keyword", "회").andExpect(status().isOk());

		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs", Long.class)).isZero();
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM user_status_histories", Long.class)).isZero();
	}

	/** @param params 이름, 값 순서. URI 템플릿 인코딩을 거치지 않도록 요청 파라미터로 넣는다 */
	private ResultActions list(String... params) throws Exception {
		MockHttpServletRequestBuilder request = get(PATH).header("Authorization", bearer(admin));
		for (int i = 0; i < params.length; i += 2) {
			request.param(params[i], params[i + 1]);
		}
		return mockMvc.perform(request);
	}

	private static List<String> fieldNames(JsonNode node) {
		return node.properties().stream().map(Map.Entry::getKey).toList();
	}

}
