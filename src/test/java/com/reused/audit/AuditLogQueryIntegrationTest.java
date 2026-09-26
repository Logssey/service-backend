package com.reused.audit;

import static com.reused.support.AdminTestClient.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import com.reused.auth.client.OAuthProviderClient;
import com.reused.auth.mail.AuthMailSender;
import com.reused.common.pagination.CursorCodec;
import com.reused.support.AdminTestClient;
import com.reused.support.AdminTestClient.Member;

/**
 * GET /api/v1/admin/audit-logs. 행은 SQL로 넣어 시각·행위자를 고정한다(IP와 detail도 채워 응답에서 빠지는지 본다).
 * 관리자 권한 규칙(401·403)은 {@code AdminEndpointAccessIntegrationTest}가 함께 본다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AuditLogQueryIntegrationTest {

	private static final String PATH = "/api/v1/admin/audit-logs";

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

	private AdminTestClient client;
	private Member admin;
	private long operator;
	private long leaver;

	@BeforeEach
	void resetState() throws Exception {
		client = new AdminTestClient(mockMvc, objectMapper, jdbcTemplate, redisTemplate);
		client.reset();
		admin = client.signupAdmin("admin@example.com", "관리자");
		operator = client.insertUser("운영자2");
		leaver = client.insertUser("곧탈퇴");
		client.withdraw(leaver);
		// 가입이 남긴 AUTH_SIGNUP을 지우고 시각이 고정된 행만 둔다.
		jdbcTemplate.update("DELETE FROM audit_logs");
	}

	@Test
	@DisplayName("최신순(audit_log_id DESC)으로 행위자 요약과 대상·결과·시각을 주고 IP·detail은 싣지 않는다")
	void listsNewestFirstWithoutIpAndDetail() throws Exception {
		long suspend = insertAudit(admin.userId(), "USER_SUSPEND", "USER", 9L, "SUCCESS", "2026-03-15T14:00:00Z");
		long reuse = insertAudit(null, "AUTH_TOKEN_REUSE_DETECTED", "USER", 5L, "FAILURE", "2026-03-15T15:00:00Z");
		long login = insertAudit(leaver, "AUTH_LOGIN", null, null, "SUCCESS", "2026-03-15T16:00:00Z");

		String body = search()
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[*].auditLogId").value(contains((int) login, (int) reuse, (int) suspend)))
				.andExpect(jsonPath("$.items[2].actor.userId").value(admin.userId()))
				.andExpect(jsonPath("$.items[2].actor.nickname").value("관리자"))
				.andExpect(jsonPath("$.items[2].actor.profileImageUrl").value(nullValue()))
				.andExpect(jsonPath("$.items[2].action").value("USER_SUSPEND"))
				.andExpect(jsonPath("$.items[2].targetType").value("USER"))
				.andExpect(jsonPath("$.items[2].targetId").value(9))
				.andExpect(jsonPath("$.items[2].result").value("SUCCESS"))
				.andExpect(jsonPath("$.items[2].createdAt").value("2026-03-15T14:00:00Z"))
				// 시스템 행위는 행위자가 없다.
				.andExpect(jsonPath("$.items[1].actor").value(nullValue()))
				.andExpect(jsonPath("$.items[1].result").value("FAILURE"))
				// 탈퇴한 행위자는 대체 닉네임 그대로, 대상이 없는 기록은 null
				.andExpect(jsonPath("$.items[0].actor.userId").value(leaver))
				.andExpect(jsonPath("$.items[0].actor.nickname").value("탈퇴회원#" + leaver))
				.andExpect(jsonPath("$.items[0].targetType").value(nullValue()))
				.andExpect(jsonPath("$.items[0].targetId").value(nullValue()))
				.andExpect(jsonPath("$.hasNext").value(false))
				.andExpect(jsonPath("$.nextCursor").value(nullValue()))
				.andReturn().getResponse().getContentAsString();

		for (JsonNode item : objectMapper.readTree(body).get("items")) {
			assertThat(item.properties().stream().map(Map.Entry::getKey).toList()).containsExactlyInAnyOrder(
					"auditLogId", "actor", "action", "targetType", "targetId", "result", "createdAt");
		}
		assertThat(body).doesNotContain("10.0.0.1").doesNotContain("비공개 사유");
	}

	@Test
	@DisplayName("정렬은 created_at이 아니라 audit_log_id다(한 트랜잭션의 행은 시각이 같을 수 있다)")
	void ordersByIdNotTime() throws Exception {
		long first = insertAudit(admin.userId(), "NOTICE_CREATE", "NOTICE", 1L, "SUCCESS", "2026-03-15T15:00:00Z");
		long second = insertAudit(admin.userId(), "NOTICE_UPDATE", "NOTICE", 1L, "SUCCESS", "2026-03-15T14:00:00Z");

		search().andExpect(jsonPath("$.items[*].auditLogId").value(contains((int) second, (int) first)));
	}

	@Test
	@DisplayName("action은 정확히 일치하는 행만 준다")
	void filtersByAction() throws Exception {
		long a = insertAudit(admin.userId(), "USER_SUSPEND", "USER", 9L, "SUCCESS", "2026-03-15T14:00:00Z");
		insertAudit(admin.userId(), "USER_ACTIVATE", "USER", 9L, "SUCCESS", "2026-03-15T15:00:00Z");
		long b = insertAudit(operator, "USER_SUSPEND", "USER", 10L, "SUCCESS", "2026-03-15T16:00:00Z");

		search("action", "USER_SUSPEND")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[*].auditLogId").value(contains((int) b, (int) a)));
	}

	@Test
	@DisplayName("actorId는 그 행위자의 행만 준다. 시스템 행(actor NULL)은 빠진다")
	void filtersByActor() throws Exception {
		insertAudit(admin.userId(), "USER_SUSPEND", "USER", 9L, "SUCCESS", "2026-03-15T14:00:00Z");
		long mine = insertAudit(operator, "NOTICE_CREATE", "NOTICE", 3L, "SUCCESS", "2026-03-15T15:00:00Z");
		insertAudit(null, "USER_ACTIVATE", "USER", 9L, "SUCCESS", "2026-03-15T16:00:00Z");

		search("actorId", String.valueOf(operator))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[*].auditLogId").value(contains((int) mine)))
				.andExpect(jsonPath("$.items[0].actor.nickname").value("운영자2"));
		search("actorId", "999")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(0));
	}

	@Test
	@DisplayName("기간은 from·to 모두 끝값을 포함한다. 한쪽만 줘도 되고 from = to도 된다")
	void filtersByPeriodInclusive() throws Exception {
		long beforeStart = insertAudit(admin.userId(), "AUTH_LOGIN", "USER", 1L, "SUCCESS", "2026-02-28T23:59:59Z");
		long start = insertAudit(admin.userId(), "AUTH_LOGIN", "USER", 1L, "SUCCESS", "2026-03-01T00:00:00Z");
		long middle = insertAudit(admin.userId(), "AUTH_LOGIN", "USER", 1L, "SUCCESS", "2026-03-15T00:00:00Z");
		long end = insertAudit(admin.userId(), "AUTH_LOGIN", "USER", 1L, "SUCCESS", "2026-03-31T23:59:59Z");
		long afterEnd = insertAudit(admin.userId(), "AUTH_LOGIN", "USER", 1L, "SUCCESS", "2026-04-01T00:00:00Z");

		search("from", "2026-03-01T00:00:00Z", "to", "2026-03-31T23:59:59Z")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[*].auditLogId").value(contains((int) end, (int) middle, (int) start)));
		search("from", "2026-03-15T00:00:00Z")
				.andExpect(jsonPath("$.items[*].auditLogId").value(contains((int) afterEnd, (int) end, (int) middle)));
		search("to", "2026-03-01T00:00:00Z")
				.andExpect(jsonPath("$.items[*].auditLogId").value(contains((int) start, (int) beforeStart)));
		search("from", "2026-03-15T00:00:00Z", "to", "2026-03-15T00:00:00Z")
				.andExpect(jsonPath("$.items[*].auditLogId").value(contains((int) middle)));
	}

	@Test
	@DisplayName("조건을 모두 함께 쓰면 AND로 좁힌다")
	void combinesFilters() throws Exception {
		long match = insertAudit(operator, "USER_SUSPEND", "USER", 9L, "SUCCESS", "2026-03-10T00:00:00Z");
		insertAudit(operator, "USER_SUSPEND", "USER", 9L, "SUCCESS", "2026-04-10T00:00:00Z");
		insertAudit(operator, "USER_ACTIVATE", "USER", 9L, "SUCCESS", "2026-03-11T00:00:00Z");
		insertAudit(admin.userId(), "USER_SUSPEND", "USER", 9L, "SUCCESS", "2026-03-12T00:00:00Z");

		search("action", "USER_SUSPEND", "actorId", String.valueOf(operator), "from", "2026-03-01T00:00:00Z",
				"to", "2026-03-31T23:59:59Z")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[*].auditLogId").value(contains((int) match)));
	}

	@ParameterizedTest(name = "{0}={1}")
	@CsvSource({
			"action, FOO",
			"action, user_suspend",
			"actorId, abc",
			"actorId, 0",
			"actorId, -1",
			"from, yesterday",
			"to, 2026-13-01T00:00:00Z",
			"size, 0",
			"size, 101",
			"size, abc",
			"cursor, not-a-cursor",
			"cursor, eyJpZCI6ImFiYyJ9" })
	@DisplayName("잘못된 조건·크기·커서는 400 INVALID_INPUT이다")
	void invalidParameterIsRejected(String name, String value) throws Exception {
		search(name, value)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"));
	}

	@Test
	@DisplayName("enum에 없는 action은 내부 타입 이름 없이 400이다")
	void unknownActionMessage() throws Exception {
		search("action", "FOO")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("action: 값의 형식이 올바르지 않습니다."));
	}

	@Test
	@DisplayName("from이 to보다 늦으면 400이다")
	void fromAfterToIsRejected() throws Exception {
		search("from", "2026-03-31T00:00:00Z", "to", "2026-03-01T00:00:00Z")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"))
				.andExpect(jsonPath("$.message").value("조회 시작 시각은 끝 시각보다 늦을 수 없습니다."));
	}

	@Test
	@DisplayName("size를 생략하면 20건이고 nextCursor로 이어 읽는다. 마지막 페이지는 nextCursor=null")
	void defaultPageSizeAndNextPage() throws Exception {
		List<Long> ids = new ArrayList<>();
		for (int i = 0; i < 25; i++) {
			ids.add(insertAudit(admin.userId(), "AUTH_LOGIN", "USER", 1L, "SUCCESS", "2026-03-15T00:00:00Z"));
		}
		long twentieth = ids.get(5);

		String firstPage = search()
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(20))
				.andExpect(jsonPath("$.items[0].auditLogId").value(ids.get(24)))
				.andExpect(jsonPath("$.items[19].auditLogId").value(twentieth))
				.andExpect(jsonPath("$.hasNext").value(true))
				.andExpect(jsonPath("$.nextCursor").value(CursorCodec.encodeId(twentieth)))
				.andReturn().getResponse().getContentAsString();

		search("cursor", objectMapper.readTree(firstPage).get("nextCursor").asString())
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(5))
				.andExpect(jsonPath("$.items[0].auditLogId").value(ids.get(4)))
				.andExpect(jsonPath("$.items[4].auditLogId").value(ids.get(0)))
				.andExpect(jsonPath("$.hasNext").value(false))
				.andExpect(jsonPath("$.nextCursor").value(nullValue()));
	}

	@Test
	@DisplayName("size 경계 1·100과 조건이 있는 다음 페이지")
	void sizeBoundsAndFilteredCursor() throws Exception {
		long a = insertAudit(admin.userId(), "USER_SUSPEND", "USER", 9L, "SUCCESS", "2026-03-15T00:00:00Z");
		insertAudit(admin.userId(), "AUTH_LOGIN", "USER", 1L, "SUCCESS", "2026-03-15T00:00:00Z");
		long b = insertAudit(admin.userId(), "USER_SUSPEND", "USER", 9L, "SUCCESS", "2026-03-15T00:00:00Z");

		String page = search("action", "USER_SUSPEND", "size", "1")
				.andExpect(jsonPath("$.items[*].auditLogId").value(contains((int) b)))
				.andExpect(jsonPath("$.hasNext").value(true))
				.andExpect(jsonPath("$.nextCursor").value(CursorCodec.encodeId(b)))
				.andReturn().getResponse().getContentAsString();
		String nextCursor = objectMapper.readTree(page).get("nextCursor").asString();
		search("action", "USER_SUSPEND", "size", "1", "cursor", nextCursor)
				.andExpect(jsonPath("$.items[*].auditLogId").value(contains((int) a)))
				.andExpect(jsonPath("$.hasNext").value(false));
		search("size", "100")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(3));
	}

	@Test
	@DisplayName("관리자 역할 변경이 남긴 감사 행을 조회로 확인할 수 있다. 조회 자체는 감사 행을 남기지 않는다")
	void showsRecordedAdminAction() throws Exception {
		mockMvc.perform(client.json(patch("/api/v1/admin/users/" + operator + "/role"),
						Map.of("role", "ADMIN", "reason", "운영팀 합류"))
						.header("Authorization", bearer(admin)))
				.andExpect(status().isOk());
		long before = auditCount();

		search("action", "USER_ROLE_GRANT")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].actor.userId").value(admin.userId()))
				.andExpect(jsonPath("$.items[0].targetType").value("USER"))
				.andExpect(jsonPath("$.items[0].targetId").value(operator))
				.andExpect(jsonPath("$.items[0].result").value("SUCCESS"))
				.andExpect(jsonPath("$.items[0].createdAt").isString());

		assertThat(auditCount()).isEqualTo(before);
	}

	// --- helpers ---

	/** @param params 이름, 값 순서. URI 템플릿 인코딩을 거치지 않도록 요청 파라미터로 넣는다 */
	private ResultActions search(String... params) throws Exception {
		MockHttpServletRequestBuilder request = get(PATH).header("Authorization", bearer(admin));
		for (int i = 0; i < params.length; i += 2) {
			request.param(params[i], params[i + 1]);
		}
		return mockMvc.perform(request);
	}

	/** IP와 detail을 채워 넣어 응답에서 빠지는지 확인한다 */
	private long insertAudit(Long actorId, String action, String targetType, Long targetId, String result,
			String createdAt) {
		return jdbcTemplate.queryForObject("""
				INSERT INTO audit_logs
				    (actor_id, action, target_type, target_id, result, ip_address, detail, created_at)
				VALUES (?, ?, ?, ?, ?, '10.0.0.1'::inet, '{"reason": "비공개 사유"}'::jsonb, ?::timestamptz)
				RETURNING audit_log_id""", Long.class, actorId, action, targetType, targetId, result, createdAt);
	}

	private long auditCount() {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs", Long.class);
	}

}
