package com.reused.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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
import com.reused.user.entity.AuthProvider;

/**
 * 신고 접수(POST /reports)와 내 신고 목록(GET /reports/me). 존재하는 다른 도메인 대상의 해석은
 * {@link ReportContentTargetIntegrationTest}가 실제 PostgreSQL 행으로 검증한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ReportSubmissionIntegrationTest {

	private static final String REPORTS = "/api/v1/reports";
	private static final String MY_REPORTS = "/api/v1/reports/me";
	private static final String PASSWORD = "hunter22!pw";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private StringRedisTemplate redisTemplate;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@MockitoBean
	private AuthMailSender mailSender;

	@MockitoBean
	private OAuthProviderClient kakaoOAuthClient;

	private Member reporter;
	private Member target;

	@BeforeEach
	void resetState() throws Exception {
		jdbcTemplate.execute("TRUNCATE audit_logs, notifications, reports, blocks, notification_settings, "
				+ "user_status_histories, user_identities, users RESTART IDENTITY CASCADE");
		redisTemplate.execute((RedisCallback<Void>) connection -> {
			connection.serverCommands().flushDb();
			return null;
		});
		given(kakaoOAuthClient.provider()).willReturn(AuthProvider.KAKAO);
		reporter = signup("reporter@example.com", "신고자");
		target = signup("target@example.com", "대상회원");
	}

	// --- POST /reports: 정상 ---

	@Test
	@DisplayName("회원을 신고하면 201과 RECEIVED이고, 상세는 앞뒤 공백을 떼어 저장하며 처리 필드는 비어 있다")
	void reportUser() throws Exception {
		mockMvc.perform(report(reporter, "USER", target.userId(), "FRAUD_SUSPICION", "  거래 후 연락 두절  "))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.reportId").value(1))
				.andExpect(jsonPath("$.status").value("RECEIVED"));

		Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM reports WHERE report_id = 1");
		assertThat(row).containsEntry("reporter_id", reporter.userId()).containsEntry("target_type", "USER")
				.containsEntry("target_id", target.userId()).containsEntry("reason_code", "FRAUD_SUSPICION")
				.containsEntry("detail", "거래 후 연락 두절").containsEntry("status", "RECEIVED")
				.containsEntry("handled_by", null).containsEntry("handled_at", null)
				.containsEntry("resolution", null);
		assertThat(row.get("created_at")).isNotNull();
	}

	@Test
	@DisplayName("신고 접수는 감사 로그와 알림을 남기지 않는다")
	void reportHasNoAuditOrNotification() throws Exception {
		mockMvc.perform(report(reporter, "USER", target.userId(), "OTHER", null))
				.andExpect(status().isCreated());

		assertThat(nonAuthAuditCount()).isZero();
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM notifications", Long.class)).isZero();
	}

	@Test
	@DisplayName("상세를 생략하거나 공백만 보내면 NULL로 저장한다. 정확히 500자는 받는다")
	void detailIsOptional() throws Exception {
		mockMvc.perform(report(reporter, "USER", target.userId(), "OTHER", null))
				.andExpect(status().isCreated());
		mockMvc.perform(report(reporter, "USER", target.userId(), "NO_SHOW", "   "))
				.andExpect(status().isCreated());
		mockMvc.perform(report(reporter, "USER", target.userId(), "ABUSIVE_BEHAVIOR", "가".repeat(500)))
				.andExpect(status().isCreated());

		assertThat(jdbcTemplate.queryForList("SELECT detail FROM reports ORDER BY report_id", String.class))
				.containsExactly(null, null, "가".repeat(500));
	}

	@Test
	@DisplayName("같은 대상이라도 사유가 다르면 따로 접수된다")
	void differentReasonIsSeparateReport() throws Exception {
		mockMvc.perform(report(reporter, "USER", target.userId(), "FRAUD_SUSPICION", null))
				.andExpect(status().isCreated());
		mockMvc.perform(report(reporter, "USER", target.userId(), "NO_SHOW", null))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.reportId").value(2));
	}

	@Test
	@DisplayName("다른 신고자는 같은 대상·사유로 신고할 수 있다")
	void otherReporterMayReportSameTarget() throws Exception {
		Member other = signup("other@example.com", "다른신고자");
		mockMvc.perform(report(reporter, "USER", target.userId(), "FRAUD_SUSPICION", null))
				.andExpect(status().isCreated());

		mockMvc.perform(report(other, "USER", target.userId(), "FRAUD_SUSPICION", null))
				.andExpect(status().isCreated());
	}

	@ParameterizedTest(name = "기존 신고 {0}")
	@ValueSource(strings = { "RESOLVED", "REJECTED" })
	@DisplayName("처리가 끝난 신고가 있으면 같은 사유로 다시 신고할 수 있다")
	void reportAgainAfterClosed(String closedStatus) throws Exception {
		mockMvc.perform(report(reporter, "USER", target.userId(), "FRAUD_SUSPICION", null))
				.andExpect(status().isCreated());
		jdbcTemplate.update("UPDATE reports SET status = ?, handled_at = now(), resolution = '처리' WHERE report_id = 1",
				closedStatus);

		mockMvc.perform(report(reporter, "USER", target.userId(), "FRAUD_SUSPICION", null))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.reportId").value(2));
	}

	@Test
	@DisplayName("차단한 상대도 신고할 수 있다(차단은 신고와 독립)")
	void blockedUserCanBeReported() throws Exception {
		jdbcTemplate.update("INSERT INTO blocks (blocker_id, blocked_id) VALUES (?, ?)", reporter.userId(),
				target.userId());

		mockMvc.perform(report(reporter, "USER", target.userId(), "ABUSIVE_BEHAVIOR", null))
				.andExpect(status().isCreated());
	}

	@Test
	@DisplayName("정지 중인 회원도 신고 대상이 될 수 있다")
	void suspendedUserCanBeReported() throws Exception {
		jdbcTemplate.update("UPDATE users SET status = 'SUSPENDED', suspended_until = NULL WHERE user_id = ?",
				target.userId());

		mockMvc.perform(report(reporter, "USER", target.userId(), "ABUSIVE_BEHAVIOR", null))
				.andExpect(status().isCreated());
	}

	@Test
	@DisplayName("관리자 토큰으로도 신고를 접수할 수 있다")
	void adminCanReport() throws Exception {
		Member admin = admin("admin@example.com", "관리자");

		mockMvc.perform(report(admin, "USER", target.userId(), "OTHER", null))
				.andExpect(status().isCreated());
	}

	// --- POST /reports: 오류 ---

	@ParameterizedTest(name = "기존 신고 {0}")
	@ValueSource(strings = { "RECEIVED", "IN_REVIEW" })
	@DisplayName("같은 대상·사유의 미처리 신고가 있으면 409이고 행이 늘지 않는다")
	void duplicatePendingReportIsConflict(String pendingStatus) throws Exception {
		mockMvc.perform(report(reporter, "USER", target.userId(), "FRAUD_SUSPICION", null))
				.andExpect(status().isCreated());
		jdbcTemplate.update("UPDATE reports SET status = ? WHERE report_id = 1", pendingStatus);

		mockMvc.perform(report(reporter, "USER", target.userId(), "FRAUD_SUSPICION", "다시"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("CONFLICT"))
				.andExpect(jsonPath("$.message").value("이미 접수된 신고입니다."));

		assertThat(reportCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("중복 확인과 INSERT 사이에 같은 미처리 신고가 먼저 커밋되면 부분 UNIQUE 인덱스가 막아 409다")
	void duplicateRaceIsConflict() throws Exception {
		CountDownLatch inserted = new CountDownLatch(1);
		CountDownLatch commit = new CountDownLatch(1);
		CompletableFuture<Void> rival = CompletableFuture.runAsync(() -> new TransactionTemplate(transactionManager)
				.executeWithoutResult(status -> {
					insertReport(reporter.userId(), "USER", target.userId(), "FRAUD_SUSPICION", "RECEIVED");
					inserted.countDown();
					awaitQuietly(commit);
				}));
		assertThat(inserted.await(10, TimeUnit.SECONDS)).isTrue();

		// 앞 INSERT는 커밋 전이라 API의 중복 확인에 보이지 않는다. API의 INSERT는 부분 UNIQUE 인덱스에서 기다린다.
		CompletableFuture<MvcResult> request = CompletableFuture.supplyAsync(
				() -> perform(report(reporter, "USER", target.userId(), "FRAUD_SUSPICION", null)));
		awaitLockWait();
		commit.countDown();
		rival.join();

		MvcResult result = request.join();
		assertThat(result.getResponse().getStatus()).isEqualTo(409);
		assertThat(readJson(result).get("message").asString()).isEqualTo("이미 접수된 신고입니다.");
		assertThat(reportCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("본인을 신고하면 400이다")
	void selfReportIsInvalid() throws Exception {
		mockMvc.perform(report(reporter, "USER", reporter.userId(), "OTHER", null))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"))
				.andExpect(jsonPath("$.message").value("본인은 신고할 수 없습니다."));

		assertThat(reportCount()).isZero();
	}

	@ParameterizedTest(name = "USER + {0}")
	@ValueSource(strings = { "SPAM", "PROHIBITED_ITEM", "FALSE_INFO", "SEXUAL_CONTENT" })
	@DisplayName("대상 유형에 허용되지 않는 사유는 400이다")
	void reasonNotAllowedForTargetType(String reasonCode) throws Exception {
		mockMvc.perform(report(reporter, "USER", target.userId(), reasonCode, null))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"))
				.andExpect(jsonPath("$.message").value("대상 유형에 허용되지 않는 신고 사유입니다."));

		assertThat(reportCount()).isZero();
	}

	@Test
	@DisplayName("사유 검사는 대상 확인보다 먼저다. 허용되지 않는 사유면 대상이 없어도 400이다")
	void reasonIsCheckedBeforeTarget() throws Exception {
		mockMvc.perform(report(reporter, "USER", 999L, "SPAM", null))
				.andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("없는 회원을 신고하면 404다")
	void missingUserIsNotFound() throws Exception {
		mockMvc.perform(report(reporter, "USER", 999L, "OTHER", null))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_FOUND"))
				.andExpect(jsonPath("$.message").value("신고 대상을 찾을 수 없습니다."));
	}

	@Test
	@DisplayName("탈퇴한 회원은 신고할 수 없다(404)")
	void withdrawnUserIsNotFound() throws Exception {
		withdraw(target.userId());

		mockMvc.perform(report(reporter, "USER", target.userId(), "OTHER", null))
				.andExpect(status().isNotFound());
	}

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = { "LISTING", "MESSAGE", "COMMUNITY_POST", "COMMUNITY_COMMENT" })
	@DisplayName("존재하지 않는 다른 도메인 대상은 404다")
	void missingTargetOfOtherTypeIsNotFound(String targetType) throws Exception {
		mockMvc.perform(report(reporter, targetType, 101L, "OTHER", null))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_FOUND"));

		assertThat(reportCount()).isZero();
	}

	@Test
	@DisplayName("필수 필드가 빠지면 400이다")
	void requiredFields() throws Exception {
		Map<String, Object> noTargetType = body(null, target.userId(), "OTHER", null);
		Map<String, Object> noTargetId = body("USER", null, "OTHER", null);
		Map<String, Object> noReason = body("USER", target.userId(), null, null);

		mockMvc.perform(json(post(REPORTS), noTargetType).header("Authorization", reporter.bearer()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"))
				.andExpect(jsonPath("$.message").value(startsWith("targetType: ")));
		mockMvc.perform(json(post(REPORTS), noTargetId).header("Authorization", reporter.bearer()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(startsWith("targetId: ")));
		mockMvc.perform(json(post(REPORTS), noReason).header("Authorization", reporter.bearer()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(startsWith("reasonCode: ")));

		assertThat(reportCount()).isZero();
	}

	@Test
	@DisplayName("상세가 500자를 넘으면 400이다")
	void detailTooLong() throws Exception {
		mockMvc.perform(report(reporter, "USER", target.userId(), "OTHER", "가".repeat(501)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"))
				.andExpect(jsonPath("$.message").value(startsWith("detail: ")));
	}

	@Test
	@DisplayName("모르는 대상 유형·사유 코드는 본문 형식 오류 400이다")
	void unknownEnumValues() throws Exception {
		mockMvc.perform(report(reporter, "REVIEW", 1L, "OTHER", null))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"))
				.andExpect(jsonPath("$.message").value("요청 본문 형식이 올바르지 않습니다."));
		mockMvc.perform(report(reporter, "USER", target.userId(), "RUDE", null))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("요청 본문 형식이 올바르지 않습니다."));
	}

	@ParameterizedTest(name = "suspended_until = {0}")
	@ValueSource(strings = { "NULL", "now() + interval '1 day'" })
	@DisplayName("정지 중인 신고자(무기한 또는 기간 남음)는 403 USER_SUSPENDED다")
	void suspendedReporterIsForbidden(String suspendedUntil) throws Exception {
		jdbcTemplate.update("UPDATE users SET status = 'SUSPENDED', suspended_until = " + suspendedUntil
				+ " WHERE user_id = ?", reporter.userId());

		mockMvc.perform(report(reporter, "USER", target.userId(), "OTHER", null))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("USER_SUSPENDED"));

		assertThat(reportCount()).isZero();
	}

	@Test
	@DisplayName("정지 기간이 이미 지났으면 신고할 수 있다")
	void expiredSuspensionAllowsReport() throws Exception {
		jdbcTemplate.update("UPDATE users SET status = 'SUSPENDED', suspended_until = now() - interval '1 minute' "
				+ "WHERE user_id = ?", reporter.userId());

		mockMvc.perform(report(reporter, "USER", target.userId(), "OTHER", null))
				.andExpect(status().isCreated());
	}

	@Test
	@DisplayName("토큰이 없으면 401이다")
	void reportRequiresAuthentication() throws Exception {
		mockMvc.perform(json(post(REPORTS), body("USER", target.userId(), "OTHER", null)))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
	}

	@Test
	@DisplayName("탈퇴한 회원의 토큰은 401이다")
	void withdrawnReporterIsUnauthenticated() throws Exception {
		withdraw(reporter.userId());

		mockMvc.perform(report(reporter, "USER", target.userId(), "OTHER", null))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

		assertThat(reportCount()).isZero();
	}

	// --- GET /reports/me ---

	@Test
	@DisplayName("내 신고만 최신순으로 보이고, 처리 전에는 resolution·handledAt이 null이며 상세는 싣지 않는다")
	void myReports() throws Exception {
		Member other = signup("other@example.com", "다른신고자");
		mockMvc.perform(report(reporter, "USER", target.userId(), "FRAUD_SUSPICION", "상세 내용"))
				.andExpect(status().isCreated());
		mockMvc.perform(report(other, "USER", target.userId(), "FRAUD_SUSPICION", null))
				.andExpect(status().isCreated());
		mockMvc.perform(report(reporter, "USER", other.userId(), "NO_SHOW", null))
				.andExpect(status().isCreated());

		mockMvc.perform(get(MY_REPORTS).header("Authorization", reporter.bearer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items", hasSize(2)))
				.andExpect(jsonPath("$.items[0].reportId").value(3))
				.andExpect(jsonPath("$.items[0].targetType").value("USER"))
				.andExpect(jsonPath("$.items[0].targetId").value(other.userId()))
				.andExpect(jsonPath("$.items[0].reasonCode").value("NO_SHOW"))
				.andExpect(jsonPath("$.items[0].status").value("RECEIVED"))
				.andExpect(jsonPath("$.items[0].resolution").value(nullValue()))
				.andExpect(jsonPath("$.items[0].handledAt").value(nullValue()))
				.andExpect(jsonPath("$.items[0].createdAt").isString())
				.andExpect(jsonPath("$.items[0].detail").doesNotExist())
				.andExpect(jsonPath("$.items[1].reportId").value(1))
				.andExpect(jsonPath("$.nextCursor").value(nullValue()))
				.andExpect(jsonPath("$.hasNext").value(false));
	}

	@Test
	@DisplayName("처리가 끝난 신고는 resolution과 handledAt이 보인다")
	void closedReportShowsResolution() throws Exception {
		mockMvc.perform(report(reporter, "USER", target.userId(), "FRAUD_SUSPICION", null))
				.andExpect(status().isCreated());
		jdbcTemplate.update("UPDATE reports SET status = 'RESOLVED', resolution = '이용정지 처리했습니다', "
				+ "handled_at = '2026-03-15T14:00:00Z' WHERE report_id = 1");

		mockMvc.perform(get(MY_REPORTS).header("Authorization", reporter.bearer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].status").value("RESOLVED"))
				.andExpect(jsonPath("$.items[0].resolution").value("이용정지 처리했습니다"))
				.andExpect(jsonPath("$.items[0].handledAt").value("2026-03-15T14:00:00Z"));
	}

	@Test
	@DisplayName("신고가 없으면 빈 목록이다")
	void emptyMyReports() throws Exception {
		mockMvc.perform(get(MY_REPORTS).header("Authorization", reporter.bearer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items", hasSize(0)))
				.andExpect(jsonPath("$.nextCursor").value(nullValue()))
				.andExpect(jsonPath("$.hasNext").value(false));
	}

	@Test
	@DisplayName("기본 크기는 20이고 nextCursor로 이어 읽으면 겹치거나 빠지는 항목이 없다")
	void myReportsPagination() throws Exception {
		List<Long> ids = new ArrayList<>();
		for (int i = 0; i < 25; i++) {
			ids.add(insertReport(reporter.userId(), "LISTING", 1000 + i, "SPAM", "RECEIVED"));
		}
		insertReport(target.userId(), "LISTING", 5000, "SPAM", "RECEIVED");

		MvcResult first = mockMvc.perform(get(MY_REPORTS).header("Authorization", reporter.bearer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items", hasSize(20)))
				.andExpect(jsonPath("$.hasNext").value(true))
				.andExpect(jsonPath("$.nextCursor").value(notNullValue()))
				.andReturn();
		JsonNode firstPage = readJson(first);

		MvcResult second = mockMvc.perform(get(MY_REPORTS).header("Authorization", reporter.bearer())
						.param("cursor", firstPage.get("nextCursor").asString()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items", hasSize(5)))
				.andExpect(jsonPath("$.hasNext").value(false))
				.andExpect(jsonPath("$.nextCursor").value(nullValue()))
				.andReturn();

		List<Long> seen = new ArrayList<>(reportIds(firstPage));
		seen.addAll(reportIds(readJson(second)));
		assertThat(seen).containsExactlyElementsOf(ids.reversed());
	}

	@Test
	@DisplayName("size 1과 100은 받고, 문서 예시 커서(표준 Base64)는 그 id 미만부터 읽는다")
	void myReportsSizeBoundsAndDocumentedCursor() throws Exception {
		insertReport(reporter.userId(), "LISTING", 1, "SPAM", "RECEIVED");
		insertReport(reporter.userId(), "LISTING", 2, "SPAM", "RECEIVED");

		mockMvc.perform(get(MY_REPORTS).header("Authorization", reporter.bearer()).param("size", "1"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items", hasSize(1)))
				.andExpect(jsonPath("$.items[0].reportId").value(2))
				.andExpect(jsonPath("$.hasNext").value(true));
		mockMvc.perform(get(MY_REPORTS).header("Authorization", reporter.bearer()).param("size", "100"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items", hasSize(2)));
		// eyJpZCI6MTIzfQ== = {"id":123}
		mockMvc.perform(get(MY_REPORTS).header("Authorization", reporter.bearer())
						.param("cursor", "eyJpZCI6MTIzfQ=="))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items", hasSize(2)));
		// eyJpZCI6Mn0 = {"id":2}
		mockMvc.perform(get(MY_REPORTS).header("Authorization", reporter.bearer()).param("cursor", "eyJpZCI6Mn0"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items", hasSize(1)))
				.andExpect(jsonPath("$.items[0].reportId").value(1));
	}

	@ParameterizedTest(name = "size={0}")
	@ValueSource(strings = { "0", "101", "-1" })
	@DisplayName("size가 1~100 밖이면 400이다")
	void myReportsSizeOutOfRange(String size) throws Exception {
		mockMvc.perform(get(MY_REPORTS).header("Authorization", reporter.bearer()).param("size", size))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"))
				.andExpect(jsonPath("$.message").value(startsWith("size: ")));
	}

	@Test
	@DisplayName("숫자가 아닌 size와 해석할 수 없는 커서는 400이다")
	void myReportsMalformedParameters() throws Exception {
		mockMvc.perform(get(MY_REPORTS).header("Authorization", reporter.bearer()).param("size", "abc"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("size: 값의 형식이 올바르지 않습니다."));
		mockMvc.perform(get(MY_REPORTS).header("Authorization", reporter.bearer()).param("cursor", "not-a-cursor"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"))
				.andExpect(jsonPath("$.message").value("커서가 올바르지 않습니다."));
	}

	@Test
	@DisplayName("정지 중인 회원도 내 신고 목록을 볼 수 있다")
	void suspendedMemberCanListReports() throws Exception {
		insertReport(reporter.userId(), "LISTING", 1, "SPAM", "RECEIVED");
		jdbcTemplate.update("UPDATE users SET status = 'SUSPENDED', suspended_until = NULL WHERE user_id = ?",
				reporter.userId());

		mockMvc.perform(get(MY_REPORTS).header("Authorization", reporter.bearer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items", hasSize(1)));
	}

	@Test
	@DisplayName("내 신고 목록은 토큰이 없으면 401, 탈퇴한 회원이면 401이다")
	void myReportsAuthentication() throws Exception {
		mockMvc.perform(get(MY_REPORTS))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

		withdraw(reporter.userId());
		mockMvc.perform(get(MY_REPORTS).header("Authorization", reporter.bearer()))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
	}

	// --- helpers ---

	private MvcResult perform(MockHttpServletRequestBuilder request) {
		try {
			return mockMvc.perform(request).andReturn();
		}
		catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	/** 다른 세션이 잠금(UNIQUE 인덱스에서 앞 트랜잭션 대기)에 걸릴 때까지 기다린다 */
	private void awaitLockWait() throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (System.nanoTime() < deadline) {
			Long waiting = jdbcTemplate.queryForObject("SELECT count(*) FROM pg_stat_activity "
					+ "WHERE wait_event_type = 'Lock' AND datname = current_database()", Long.class);
			if (waiting != null && waiting > 0) {
				return;
			}
			Thread.sleep(20);
		}
		throw new AssertionError("API의 INSERT가 잠금 대기에 들어가지 않았다");
	}

	private static void awaitQuietly(CountDownLatch latch) {
		try {
			latch.await(10, TimeUnit.SECONDS);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private MockHttpServletRequestBuilder report(Member member, String targetType, Long targetId, String reasonCode,
			String detail) {
		return json(post(REPORTS), body(targetType, targetId, reasonCode, detail))
				.header("Authorization", member.bearer());
	}

	private static Map<String, Object> body(String targetType, Long targetId, String reasonCode, String detail) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("targetType", targetType);
		body.put("targetId", targetId);
		body.put("reasonCode", reasonCode);
		if (detail != null) {
			body.put("detail", detail);
		}
		return body;
	}

	private long insertReport(long reporterId, String targetType, long targetId, String reasonCode, String status) {
		return jdbcTemplate.queryForObject("INSERT INTO reports (reporter_id, target_type, target_id, reason_code, "
				+ "status) VALUES (?, ?, ?, ?, ?) RETURNING report_id", Long.class, reporterId, targetType, targetId,
				reasonCode, status);
	}

	private long reportCount() {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM reports", Long.class);
	}

	/** 가입·로그인 감사(AUTH_*)는 빼고 센다 */
	private long nonAuthAuditCount() {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs WHERE action NOT LIKE 'AUTH%'",
				Long.class);
	}

	private void withdraw(long userId) {
		jdbcTemplate.update("UPDATE users SET status = 'WITHDRAWN', withdrawn_at = now() WHERE user_id = ?", userId);
	}

	private List<Long> reportIds(JsonNode page) {
		List<Long> ids = new ArrayList<>();
		page.get("items").forEach(item -> ids.add(item.get("reportId").asLong()));
		return ids;
	}

	private JsonNode readJson(MvcResult result) throws Exception {
		return objectMapper.readTree(result.getResponse().getContentAsString());
	}

	private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder, Map<String, ?> body) {
		return builder.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
	}

	private Member signup(String email, String nickname) throws Exception {
		MvcResult result = mockMvc.perform(json(post("/api/v1/auth/email/signup"),
						Map.of("email", email, "password", PASSWORD, "nickname", nickname,
								"termsOfServiceAgreed", true, "privacyPolicyAgreed", true)))
				.andExpect(status().isCreated())
				.andReturn();
		JsonNode json = readJson(result);
		return new Member(json.get("user").get("userId").asLong(), json.get("accessToken").asString(),
				result.getResponse().getCookie("refresh_token").getValue());
	}

	/** 가입 → role=ADMIN → 재발급. 역할은 발급 시점 클레임이다. */
	private Member admin(String email, String nickname) throws Exception {
		Member member = signup(email, nickname);
		jdbcTemplate.update("UPDATE users SET role = 'ADMIN' WHERE user_id = ?", member.userId());
		MvcResult refreshed = mockMvc.perform(post("/api/v1/auth/refresh")
						.cookie(new Cookie("refresh_token", member.refreshToken())))
				.andExpect(status().isOk())
				.andReturn();
		return new Member(member.userId(), readJson(refreshed).get("accessToken").asString(),
				refreshed.getResponse().getCookie("refresh_token").getValue());
	}

	private record Member(long userId, String accessToken, String refreshToken) {

		String bearer() {
			return "Bearer " + accessToken;
		}

	}

}
