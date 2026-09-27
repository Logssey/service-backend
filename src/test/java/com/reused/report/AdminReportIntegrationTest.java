package com.reused.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;

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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.reused.TestcontainersConfiguration;
import com.reused.auth.client.OAuthProviderClient;
import com.reused.auth.mail.AuthMailSender;
import com.reused.auth.token.JwtTokenProvider;
import com.reused.auth.token.RefreshTokenStore;
import com.reused.user.entity.AuthProvider;
import com.reused.user.entity.UserRole;

/**
 * 관리자 신고 목록(GET /admin/reports)과 신고 처리(PATCH /admin/reports/{id}).
 *
 * <p>회원 대상 신고로 상태 전이·이용정지·알림·감사를 확인한다. 게시글 신고는 대상 도메인(A) 구현이 없는 상태로 두어,
 * 요약이 null이고 콘텐츠 조치가 503인지 본다. 대역을 붙인 경우는 {@link ReportContentTargetIntegrationTest}다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AdminReportIntegrationTest {

	private static final String ADMIN_REPORTS = "/api/v1/admin/reports";
	private static final String PASSWORD = "hunter22!pw";
	private static final String RESOLUTION = "반복된 사기 의심으로 이용정지 처리했습니다";
	private static final Duration DEFAULT_SUSPENSION = Duration.ofDays(7);

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private StringRedisTemplate redisTemplate;

	@Autowired
	private RefreshTokenStore refreshTokenStore;

	@Autowired
	private JwtTokenProvider tokenProvider;

	@MockitoBean
	private AuthMailSender mailSender;

	@MockitoBean
	private OAuthProviderClient kakaoOAuthClient;

	private Member admin;
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
		admin = admin("admin@example.com", "관리자");
		reporter = signup("reporter@example.com", "구매자A");
		target = signup("target@example.com", "대상회원");
	}

	// --- GET /admin/reports: 권한 ---

	@Test
	@DisplayName("관리자 신고 목록은 토큰이 없으면 401, USER 토큰이면 403이다")
	void listRequiresAdmin() throws Exception {
		mockMvc.perform(get(ADMIN_REPORTS))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
		mockMvc.perform(get(ADMIN_REPORTS).header("Authorization", reporter.bearer()))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("FORBIDDEN"));
	}

	@Test
	@DisplayName("ADMIN 토큰이 남아 있어도 정지·역할 회수된 관리자는 목록·처리 모두 403 FORBIDDEN이다(AdminAccessInterceptor)")
	void inactiveAdminIsForbidden() throws Exception {
		long reportId = submitUserReport(reporter, target, "FRAUD_SUSPICION", null);
		List<String> states = List.of(
				"status = 'SUSPENDED', suspended_until = NULL",
				"status = 'SUSPENDED', suspended_until = now() + interval '1 day'",
				"role = 'USER'");

		for (String state : states) {
			jdbcTemplate.update("UPDATE users SET role = 'ADMIN', status = 'ACTIVE', suspended_until = NULL "
					+ "WHERE user_id = ?", admin.userId());
			jdbcTemplate.update("UPDATE users SET " + state + " WHERE user_id = ?", admin.userId());

			mockMvc.perform(get(ADMIN_REPORTS).header("Authorization", admin.bearer()))
					.andExpect(status().isForbidden())
					.andExpect(jsonPath("$.code").value("FORBIDDEN"));
			mockMvc.perform(handle(admin, reportId, handleBody("RESOLVED", RESOLUTION, "SUSPEND_USER")))
					.andExpect(status().isForbidden())
					.andExpect(jsonPath("$.code").value("FORBIDDEN"));
		}

		assertReportUnchanged(reportId);
		assertThat(userStatus(target.userId())).isEqualTo("ACTIVE");
	}

	@Test
	@DisplayName("탈퇴한 관리자의 남은 ADMIN 토큰은 목록·처리 모두 401 UNAUTHENTICATED다(탈퇴 토큰 규칙, contracts §1.5)")
	void withdrawnAdminIsUnauthenticated() throws Exception {
		long reportId = submitUserReport(reporter, target, "FRAUD_SUSPICION", null);
		jdbcTemplate.update("UPDATE users SET status = 'WITHDRAWN', withdrawn_at = now() WHERE user_id = ?",
				admin.userId());

		assertAdminUnauthenticated(admin, reportId);
	}

	@Test
	@DisplayName("DB에 없는 사용자의 ADMIN 토큰은 목록·처리 모두 401 UNAUTHENTICATED다")
	void unknownAdminIsUnauthenticated() throws Exception {
		long reportId = submitUserReport(reporter, target, "FRAUD_SUSPICION", null);
		Member ghost = new Member(999L, tokenProvider.issueAccessToken(999L, UserRole.ADMIN), null);

		assertAdminUnauthenticated(ghost, reportId);
	}

	// --- GET /admin/reports: 내용 ---

	@Test
	@DisplayName("신고자 요약·대상 요약·상세·처리 필드를 최신순으로 담는다")
	void listShowsReports() throws Exception {
		long userReport = submitUserReport(reporter, target, "FRAUD_SUSPICION", "허위 매물로 보입니다");
		long listingReport = insertReport(reporter.userId(), "LISTING", 101, "SPAM", "RECEIVED");

		mockMvc.perform(get(ADMIN_REPORTS).header("Authorization", admin.bearer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items", hasSize(2)))
				.andExpect(jsonPath("$.items[0].reportId").value(listingReport))
				.andExpect(jsonPath("$.items[0].targetType").value("LISTING"))
				.andExpect(jsonPath("$.items[0].targetId").value(101))
				// 게시글 도메인 구현이 없으므로 대상을 확인할 수 없다
				.andExpect(jsonPath("$.items[0].targetSummary").value(nullValue()))
				.andExpect(jsonPath("$.items[1].reportId").value(userReport))
				.andExpect(jsonPath("$.items[1].reporter.userId").value(reporter.userId()))
				.andExpect(jsonPath("$.items[1].reporter.nickname").value("구매자A"))
				.andExpect(jsonPath("$.items[1].reporter.profileImageUrl").value(nullValue()))
				.andExpect(jsonPath("$.items[1].targetType").value("USER"))
				.andExpect(jsonPath("$.items[1].targetId").value(target.userId()))
				.andExpect(jsonPath("$.items[1].targetSummary").value("대상회원"))
				.andExpect(jsonPath("$.items[1].reasonCode").value("FRAUD_SUSPICION"))
				.andExpect(jsonPath("$.items[1].detail").value("허위 매물로 보입니다"))
				.andExpect(jsonPath("$.items[1].status").value("RECEIVED"))
				.andExpect(jsonPath("$.items[1].handledBy").value(nullValue()))
				.andExpect(jsonPath("$.items[1].resolution").value(nullValue()))
				.andExpect(jsonPath("$.items[1].createdAt").isString())
				.andExpect(jsonPath("$.hasNext").value(false))
				.andExpect(jsonPath("$.nextCursor").value(nullValue()));
	}

	@Test
	@DisplayName("탈퇴한 신고자·대상은 바뀐 닉네임(탈퇴회원#id)으로 보인다")
	void withdrawnUsersInList() throws Exception {
		submitUserReport(reporter, target, "NO_SHOW", null);
		jdbcTemplate.update("UPDATE users SET nickname = '탈퇴회원#' || user_id, status = 'WITHDRAWN', "
				+ "withdrawn_at = now() WHERE user_id IN (?, ?)", reporter.userId(), target.userId());

		mockMvc.perform(get(ADMIN_REPORTS).header("Authorization", admin.bearer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].reporter.nickname").value("탈퇴회원#" + reporter.userId()))
				.andExpect(jsonPath("$.items[0].targetSummary").value("탈퇴회원#" + target.userId()));
	}

	@Test
	@DisplayName("처리한 신고는 handledBy(관리자 id)와 resolution이 보인다")
	void listShowsHandler() throws Exception {
		long reportId = submitUserReport(reporter, target, "OTHER", null);
		mockMvc.perform(handle(admin, reportId, handleBody("REJECTED", "근거 부족", null)))
				.andExpect(status().isOk());

		mockMvc.perform(get(ADMIN_REPORTS).header("Authorization", admin.bearer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].status").value("REJECTED"))
				.andExpect(jsonPath("$.items[0].handledBy").value(admin.userId()))
				.andExpect(jsonPath("$.items[0].resolution").value("근거 부족"));
	}

	@Test
	@DisplayName("status·targetType 필터는 따로도 함께도 적용된다")
	void listFilters() throws Exception {
		long receivedUser = insertReport(reporter.userId(), "USER", target.userId(), "OTHER", "RECEIVED");
		long resolvedUser = insertReport(reporter.userId(), "USER", target.userId(), "NO_SHOW", "RESOLVED");
		long receivedListing = insertReport(reporter.userId(), "LISTING", 101, "SPAM", "RECEIVED");
		long reviewingMessage = insertReport(reporter.userId(), "MESSAGE", 501, "SPAM", "IN_REVIEW");

		assertThat(listIds(Map.of("status", "RECEIVED"))).containsExactly(receivedListing, receivedUser);
		assertThat(listIds(Map.of("targetType", "USER"))).containsExactly(resolvedUser, receivedUser);
		assertThat(listIds(Map.of("status", "RECEIVED", "targetType", "USER"))).containsExactly(receivedUser);
		assertThat(listIds(Map.of("status", "IN_REVIEW"))).containsExactly(reviewingMessage);
		assertThat(listIds(Map.of("targetType", "COMMUNITY_COMMENT"))).isEmpty();
		assertThat(listIds(Map.of())).containsExactly(reviewingMessage, receivedListing, resolvedUser, receivedUser);
	}

	@Test
	@DisplayName("모르는 status·targetType 필터 값은 400이다")
	void listRejectsUnknownFilterValues() throws Exception {
		mockMvc.perform(get(ADMIN_REPORTS).header("Authorization", admin.bearer()).param("status", "DONE"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"))
				.andExpect(jsonPath("$.message").value("status: 값의 형식이 올바르지 않습니다."));
		mockMvc.perform(get(ADMIN_REPORTS).header("Authorization", admin.bearer()).param("targetType", "REVIEW"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("targetType: 값의 형식이 올바르지 않습니다."));
	}

	@Test
	@DisplayName("기본 크기는 20이고 nextCursor로 이어 읽는다. 필터를 유지한 채 다음 페이지도 읽힌다")
	void listPagination() throws Exception {
		List<Long> received = new ArrayList<>();
		for (int i = 0; i < 25; i++) {
			long id = insertReport(reporter.userId(), "LISTING", 1000 + i, "SPAM", "RECEIVED");
			received.add(id);
			if (i % 5 == 0) {
				insertReport(reporter.userId(), "LISTING", 2000 + i, "SPAM", "RESOLVED");
			}
		}

		MvcResult first = mockMvc.perform(get(ADMIN_REPORTS).header("Authorization", admin.bearer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items", hasSize(20)))
				.andExpect(jsonPath("$.hasNext").value(true))
				.andExpect(jsonPath("$.nextCursor").value(notNullValue()))
				.andReturn();
		mockMvc.perform(get(ADMIN_REPORTS).header("Authorization", admin.bearer())
						.param("cursor", readJson(first).get("nextCursor").asString()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items", hasSize(10)))
				.andExpect(jsonPath("$.hasNext").value(false))
				.andExpect(jsonPath("$.nextCursor").value(nullValue()));

		List<Long> seen = new ArrayList<>();
		String cursor = null;
		do {
			MockHttpServletRequestBuilder request = get(ADMIN_REPORTS).header("Authorization", admin.bearer())
					.param("status", "RECEIVED").param("size", "10");
			if (cursor != null) {
				request.param("cursor", cursor);
			}
			JsonNode page = readJson(mockMvc.perform(request).andExpect(status().isOk()).andReturn());
			page.get("items").forEach(item -> seen.add(item.get("reportId").asLong()));
			cursor = page.get("hasNext").asBoolean() ? page.get("nextCursor").asString() : null;
		}
		while (cursor != null);
		assertThat(seen).containsExactlyElementsOf(received.reversed());
	}

	@ParameterizedTest(name = "size={0}")
	@ValueSource(strings = { "0", "101" })
	@DisplayName("size가 1~100 밖이면 400이다")
	void listSizeOutOfRange(String size) throws Exception {
		mockMvc.perform(get(ADMIN_REPORTS).header("Authorization", admin.bearer()).param("size", size))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"))
				.andExpect(jsonPath("$.message").value(startsWith("size: ")));
	}

	@Test
	@DisplayName("size 1·100은 받고 해석할 수 없는 커서는 400이다")
	void listSizeBoundsAndBadCursor() throws Exception {
		insertReport(reporter.userId(), "LISTING", 1, "SPAM", "RECEIVED");
		insertReport(reporter.userId(), "LISTING", 2, "SPAM", "RECEIVED");

		mockMvc.perform(get(ADMIN_REPORTS).header("Authorization", admin.bearer()).param("size", "1"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items", hasSize(1)))
				.andExpect(jsonPath("$.hasNext").value(true));
		mockMvc.perform(get(ADMIN_REPORTS).header("Authorization", admin.bearer()).param("size", "100"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items", hasSize(2)));
		mockMvc.perform(get(ADMIN_REPORTS).header("Authorization", admin.bearer()).param("cursor", "%%%"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("커서가 올바르지 않습니다."));
	}

	// --- PATCH /admin/reports/{id}: 상태 전이 ---

	@Test
	@DisplayName("검토 시작(IN_REVIEW)은 처리 관리자만 남기고 처리 시각·결과는 비워 둔다. 감사는 남고 알림은 없다")
	void startReview() throws Exception {
		long reportId = submitUserReport(reporter, target, "FRAUD_SUSPICION", null);

		mockMvc.perform(handle(admin, reportId, handleBody("IN_REVIEW", "확인 중", null)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.reportId").value(reportId))
				.andExpect(jsonPath("$.status").value("IN_REVIEW"))
				.andExpect(jsonPath("$.handledAt").value(nullValue()));

		assertThat(reportRow(reportId)).containsEntry("status", "IN_REVIEW")
				.containsEntry("handled_by", admin.userId()).containsEntry("handled_at", null)
				.containsEntry("resolution", null);
		List<Map<String, Object>> audits = audits();
		assertThat(audits).singleElement().satisfies(audit -> assertThat(audit)
				.containsEntry("action", "REPORT_HANDLE").containsEntry("actor_id", admin.userId())
				.containsEntry("target_type", "REPORT").containsEntry("target_id", reportId)
				.containsEntry("result", "SUCCESS"));
		JsonNode detail = detail(audits.get(0));
		assertThat(detail.get("before").asString()).isEqualTo("RECEIVED");
		assertThat(detail.get("after").asString()).isEqualTo("IN_REVIEW");
		assertThat(detail.get("action").asString()).isEqualTo("NONE");
		assertThat(detail.get("targetType").asString()).isEqualTo("USER");
		assertThat(detail.get("targetId").asLong()).isEqualTo(target.userId());
		assertThat(notifications()).isEmpty();
	}

	@Test
	@DisplayName("이미 검토 중인 신고를 다시 IN_REVIEW로 바꾸면 아무것도 바뀌지 않는 200이다")
	void startReviewTwiceIsNoOp() throws Exception {
		long reportId = submitUserReport(reporter, target, "FRAUD_SUSPICION", null);
		Member otherAdmin = admin("admin2@example.com", "관리자2");
		mockMvc.perform(handle(admin, reportId, handleBody("IN_REVIEW", null, null)))
				.andExpect(status().isOk());

		mockMvc.perform(handle(otherAdmin, reportId, handleBody("IN_REVIEW", null, "NONE")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("IN_REVIEW"));

		assertThat(reportRow(reportId)).containsEntry("handled_by", admin.userId());
		assertThat(audits()).hasSize(1);
	}

	@Test
	@DisplayName("검토 중인 신고를 조치 없이 처리하면 결과·시각이 남고 신고자에게 '신고가 처리되었습니다' 알림이 간다")
	void resolveWithoutAction() throws Exception {
		long reportId = submitUserReport(reporter, target, "FRAUD_SUSPICION", null);
		mockMvc.perform(handle(admin, reportId, handleBody("IN_REVIEW", null, null)))
				.andExpect(status().isOk());
		Instant before = Instant.now();

		MvcResult result = mockMvc.perform(handle(admin, reportId, handleBody("RESOLVED", "  주의 안내했습니다  ", null)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.reportId").value(reportId))
				.andExpect(jsonPath("$.status").value("RESOLVED"))
				.andExpect(jsonPath("$.handledAt").isString())
				.andReturn();

		Map<String, Object> row = reportRow(reportId);
		assertThat(row).containsEntry("status", "RESOLVED").containsEntry("handled_by", admin.userId())
				.containsEntry("resolution", "주의 안내했습니다");
		Instant handledAt = ((Timestamp) row.get("handled_at")).toInstant();
		assertThat(handledAt).isBetween(before.minusSeconds(1), Instant.now().plusSeconds(1));
		assertThat(readJson(result).get("handledAt").asString()).isEqualTo(handledAt.toString());

		List<Map<String, Object>> audits = audits();
		assertThat(audits).hasSize(2);
		JsonNode detail = detail(audits.get(1));
		assertThat(detail.get("before").asString()).isEqualTo("IN_REVIEW");
		assertThat(detail.get("after").asString()).isEqualTo("RESOLVED");
		assertThat(detail.get("action").asString()).isEqualTo("NONE");
		assertThat(detail.has("resolution")).isFalse();

		assertThat(notifications()).singleElement().satisfies(notification -> assertThat(notification)
				.containsEntry("user_id", reporter.userId()).containsEntry("type", "REPORT_RESOLVED")
				.containsEntry("title", "신고 처리가 완료되었습니다").containsEntry("body", "신고가 처리되었습니다")
				.containsEntry("target_type", "REPORT").containsEntry("target_id", reportId)
				.containsEntry("read_at", null));
		assertThat(userStatus(target.userId())).isEqualTo("ACTIVE");

		mockMvc.perform(get("/api/v1/reports/me").header("Authorization", reporter.bearer()))
				.andExpect(jsonPath("$.items[0].status").value("RESOLVED"))
				.andExpect(jsonPath("$.items[0].resolution").value("주의 안내했습니다"))
				.andExpect(jsonPath("$.items[0].handledAt").value(handledAt.toString()));
	}

	@Test
	@DisplayName("반려(REJECTED)도 결과를 남기고 신고자에게 '신고가 반려되었습니다' 알림을 보낸다")
	void reject() throws Exception {
		long reportId = submitUserReport(reporter, target, "OTHER", null);

		mockMvc.perform(handle(admin, reportId, handleBody("REJECTED", "위반 사항을 확인하지 못했습니다", "NONE")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("REJECTED"))
				.andExpect(jsonPath("$.handledAt").isString());

		assertThat(reportRow(reportId)).containsEntry("status", "REJECTED")
				.containsEntry("resolution", "위반 사항을 확인하지 못했습니다").containsEntry("handled_by", admin.userId());
		assertThat(detail(audits().get(0)).get("after").asString()).isEqualTo("REJECTED");
		assertThat(notifications()).singleElement().satisfies(notification -> assertThat(notification)
				.containsEntry("body", "신고가 반려되었습니다").containsEntry("target_id", reportId));
		assertThat(userStatus(target.userId())).isEqualTo("ACTIVE");
	}

	@Test
	@DisplayName("신고 처리 결과 알림을 꺼 둔 신고자에게는 알림을 만들지 않는다. 처리는 그대로 된다")
	void reportNotificationDisabled() throws Exception {
		long reportId = submitUserReport(reporter, target, "OTHER", null);
		jdbcTemplate.update("UPDATE notification_settings SET report_enabled = false WHERE user_id = ?",
				reporter.userId());

		mockMvc.perform(handle(admin, reportId, handleBody("RESOLVED", "처리했습니다", null)))
				.andExpect(status().isOk());

		assertThat(reportRow(reportId)).containsEntry("status", "RESOLVED");
		assertThat(notifications()).isEmpty();
	}

	// --- PATCH: SUSPEND_USER ---

	@Test
	@DisplayName("SUSPEND_USER는 대상 회원을 기본 7일 정지하고 이력·감사를 남기며 커밋 뒤 Refresh Token을 모두 폐기한다")
	void suspendReportedUser() throws Exception {
		long reportId = submitUserReport(reporter, target, "FRAUD_SUSPICION", null);
		refreshTokenStore.issue(target.userId());
		assertThat(refreshKeys(target.userId())).hasSize(2);
		Instant before = Instant.now();

		mockMvc.perform(handle(admin, reportId, handleBody("RESOLVED", RESOLUTION, "SUSPEND_USER")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("RESOLVED"));

		assertThat(userStatus(target.userId())).isEqualTo("SUSPENDED");
		Instant until = suspendedUntil(target.userId());
		assertThat(until).isBetween(before.plus(DEFAULT_SUSPENSION).minusSeconds(1),
				Instant.now().plus(DEFAULT_SUSPENSION).plusSeconds(1));
		assertThat(jdbcTemplate.queryForList("SELECT user_id, change_type, before_value, after_value, reason, "
				+ "changed_by FROM user_status_histories")).singleElement().satisfies(history -> assertThat(history)
				.containsEntry("user_id", target.userId()).containsEntry("change_type", "STATUS")
				.containsEntry("before_value", "ACTIVE").containsEntry("after_value", "SUSPENDED")
				.containsEntry("reason", RESOLUTION).containsEntry("changed_by", admin.userId()));

		assertThat(refreshKeys(target.userId())).isEmpty();
		assertThat(refreshKeys(reporter.userId())).hasSize(1);

		List<Map<String, Object>> audits = audits();
		assertThat(audits).extracting(audit -> audit.get("action")).containsExactly("USER_SUSPEND", "REPORT_HANDLE");
		assertThat(audits.get(0)).containsEntry("actor_id", admin.userId()).containsEntry("target_type", "USER")
				.containsEntry("target_id", target.userId());
		JsonNode suspendDetail = detail(audits.get(0));
		assertThat(suspendDetail.get("source").asString()).isEqualTo("REPORT");
		assertThat(suspendDetail.get("reportId").asLong()).isEqualTo(reportId);
		assertThat(suspendDetail.get("reason").asString()).isEqualTo(RESOLUTION);
		assertThat(detail(audits.get(1)).get("action").asString()).isEqualTo("SUSPEND_USER");

		assertThat(notifications()).singleElement().satisfies(notification -> assertThat(notification)
				.containsEntry("user_id", reporter.userId()).containsEntry("body", "이용정지 조치"));

		// 정지된 회원은 남은 Access Token으로도 신고를 접수할 수 없다
		mockMvc.perform(json(post("/api/v1/reports"), Map.of("targetType", "USER", "targetId", reporter.userId(),
						"reasonCode", "OTHER")).header("Authorization", target.bearer()))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("USER_SUSPENDED"));
	}

	@Test
	@DisplayName("SUSPEND_USER 대상이 처리하는 관리자 본인이면 403이고 아무것도 바뀌지 않는다")
	void suspendSelfIsForbidden() throws Exception {
		long reportId = submitUserReport(reporter, admin, "ABUSIVE_BEHAVIOR", null);

		mockMvc.perform(handle(admin, reportId, handleBody("RESOLVED", RESOLUTION, "SUSPEND_USER")))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("FORBIDDEN"));

		assertReportUnchanged(reportId);
		assertThat(userStatus(admin.userId())).isEqualTo("ACTIVE");
	}

	@Test
	@DisplayName("SUSPEND_USER 대상이 이미 탈퇴했으면 409이고 신고는 그대로다")
	void suspendWithdrawnTargetIsConflict() throws Exception {
		long reportId = submitUserReport(reporter, target, "ABUSIVE_BEHAVIOR", null);
		jdbcTemplate.update("UPDATE users SET status = 'WITHDRAWN', withdrawn_at = now() WHERE user_id = ?",
				target.userId());

		mockMvc.perform(handle(admin, reportId, handleBody("RESOLVED", RESOLUTION, "SUSPEND_USER")))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("CONFLICT"));

		assertReportUnchanged(reportId);
	}

	@Test
	@DisplayName("감사 기록이 실패하면 정지와 신고 처리가 모두 롤백되고 토큰도 폐기되지 않는다")
	void auditFailureRollsBackEverything() throws Exception {
		long reportId = submitUserReport(reporter, target, "FRAUD_SUSPICION", null);
		jdbcTemplate.execute("ALTER TABLE audit_logs RENAME TO audit_logs_unavailable");
		try {
			mockMvc.perform(handle(admin, reportId, handleBody("RESOLVED", RESOLUTION, "SUSPEND_USER")))
					.andExpect(status().isInternalServerError())
					.andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
		}
		finally {
			jdbcTemplate.execute("ALTER TABLE audit_logs_unavailable RENAME TO audit_logs");
		}

		assertReportUnchanged(reportId);
		assertThat(userStatus(target.userId())).isEqualTo("ACTIVE");
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM user_status_histories", Long.class)).isZero();
		assertThat(refreshKeys(target.userId())).hasSize(1);
	}

	// --- PATCH: 대상이 존재하지 않을 때 ---

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = { "HIDE_LISTING", "DELETE_LISTING" })
	@DisplayName("신고 대상 상품이 없으면 404이고 신고는 그대로다")
	void contentActionWithMissingListingIsNotFound(String action) throws Exception {
		long reportId = insertReport(reporter.userId(), "LISTING", 101, "SPAM", "RECEIVED");

		mockMvc.perform(handle(admin, reportId, handleBody("RESOLVED", "허위 매물", action)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_FOUND"));

		assertReportUnchanged(reportId);
	}

	@Test
	@DisplayName("게시글 도메인 구현이 없으면 SUSPEND_USER가 정지할 회원을 알 수 없어 404다")
	void suspendOwnerWithoutResolverIsNotFound() throws Exception {
		long reportId = insertReport(reporter.userId(), "LISTING", 101, "SPAM", "RECEIVED");

		mockMvc.perform(handle(admin, reportId, handleBody("RESOLVED", "허위 매물", "SUSPEND_USER")))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value("정지할 회원을 찾을 수 없습니다."));

		assertReportUnchanged(reportId);
	}

	@Test
	@DisplayName("조치 없는 처리는 대상 도메인 구현이 없어도 된다")
	void resolveWithoutActionNeedsNoPort() throws Exception {
		long reportId = insertReport(reporter.userId(), "LISTING", 101, "SPAM", "RECEIVED");

		mockMvc.perform(handle(admin, reportId, handleBody("RESOLVED", "경고 조치", "NONE")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("RESOLVED"));
	}

	// --- PATCH: 오류 ---

	@Test
	@DisplayName("RESOLVED·REJECTED에서 처리 결과가 없거나 공백이면 400이다")
	void resolutionIsRequired() throws Exception {
		long reportId = submitUserReport(reporter, target, "OTHER", null);

		for (Map<String, Object> body : List.of(handleBody("RESOLVED", null, null), handleBody("RESOLVED", "   ", null),
				handleBody("REJECTED", null, null), handleBody("REJECTED", "", "NONE"))) {
			mockMvc.perform(handle(admin, reportId, body))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("INVALID_INPUT"))
					.andExpect(jsonPath("$.message").value("처리 결과(resolution)를 입력해 주세요."));
		}

		assertReportUnchanged(reportId);
	}

	@Test
	@DisplayName("처리 결과가 500자를 넘으면 400이다")
	void resolutionTooLong() throws Exception {
		long reportId = submitUserReport(reporter, target, "OTHER", null);

		mockMvc.perform(handle(admin, reportId, handleBody("RESOLVED", "가".repeat(501), null)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(startsWith("resolution: ")));

		assertReportUnchanged(reportId);
	}

	@ParameterizedTest(name = "기존 {0}")
	@ValueSource(strings = { "RESOLVED", "REJECTED" })
	@DisplayName("이미 최종 상태인 신고는 어떤 상태로도 바꿀 수 없다(409)")
	void closedReportIsConflict(String closedStatus) throws Exception {
		long reportId = submitUserReport(reporter, target, "OTHER", null);
		mockMvc.perform(handle(admin, reportId, handleBody(closedStatus, "처리", null)))
				.andExpect(status().isOk());

		for (Map<String, Object> body : List.of(handleBody("RESOLVED", "재처리", null),
				handleBody("REJECTED", "재처리", null), handleBody("IN_REVIEW", null, null),
				handleBody("RESOLVED", "재처리", "SUSPEND_USER"))) {
			mockMvc.perform(handle(admin, reportId, body))
					.andExpect(status().isConflict())
					.andExpect(jsonPath("$.code").value("CONFLICT"))
					.andExpect(jsonPath("$.message").value("이미 처리가 끝난 신고입니다."));
		}

		assertThat(reportRow(reportId)).containsEntry("status", closedStatus).containsEntry("resolution", "처리");
		assertThat(audits()).hasSize(1);
		assertThat(notifications()).hasSize(1);
		assertThat(userStatus(target.userId())).isEqualTo("ACTIVE");
	}

	@Test
	@DisplayName("없는 신고는 404다")
	void missingReportIsNotFound() throws Exception {
		mockMvc.perform(handle(admin, 999L, handleBody("RESOLVED", "처리", null)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_FOUND"))
				.andExpect(jsonPath("$.message").value("신고를 찾을 수 없습니다."));
	}

	@Test
	@DisplayName("숫자가 아닌 reportId는 400이다")
	void nonNumericReportId() throws Exception {
		mockMvc.perform(json(patch(ADMIN_REPORTS + "/abc"), handleBody("RESOLVED", "처리", null))
						.header("Authorization", admin.bearer()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("reportId: 값의 형식이 올바르지 않습니다."));
	}

	@Test
	@DisplayName("status가 없거나 RECEIVED이거나 모르는 값이면 400이다")
	void invalidTargetStatus() throws Exception {
		long reportId = submitUserReport(reporter, target, "OTHER", null);

		mockMvc.perform(handle(admin, reportId, handleBody(null, "처리", null)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(startsWith("status: ")));
		mockMvc.perform(handle(admin, reportId, handleBody("RECEIVED", "처리", null)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("접수(RECEIVED) 상태로는 바꿀 수 없습니다."));
		mockMvc.perform(handle(admin, reportId, handleBody("DONE", "처리", null)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("요청 본문 형식이 올바르지 않습니다."));
		mockMvc.perform(handle(admin, reportId, handleBody("RESOLVED", "처리", "BAN_FOREVER")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("요청 본문 형식이 올바르지 않습니다."));

		assertReportUnchanged(reportId);
	}

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = { "HIDE_LISTING", "DELETE_LISTING", "HIDE_COMMUNITY_POST", "HIDE_COMMUNITY_COMMENT" })
	@DisplayName("회원 대상 신고에 콘텐츠 조치를 지정하면 400이다")
	void actionMustMatchTarget(String action) throws Exception {
		long reportId = submitUserReport(reporter, target, "OTHER", null);

		mockMvc.perform(handle(admin, reportId, handleBody("RESOLVED", "처리", action)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("신고 대상에 맞지 않는 조치입니다."));

		assertReportUnchanged(reportId);
	}

	@Test
	@DisplayName("조치는 RESOLVED에서만 지정할 수 있다. 검토 시작·반려에 조치를 붙이면 400이다")
	void actionOnlyWithResolved() throws Exception {
		long reportId = submitUserReport(reporter, target, "OTHER", null);

		mockMvc.perform(handle(admin, reportId, handleBody("REJECTED", "반려", "SUSPEND_USER")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("조치는 처리 완료(RESOLVED)에서만 지정할 수 있습니다."));
		mockMvc.perform(handle(admin, reportId, handleBody("IN_REVIEW", null, "SUSPEND_USER")))
				.andExpect(status().isBadRequest());

		assertReportUnchanged(reportId);
		assertThat(userStatus(target.userId())).isEqualTo("ACTIVE");
	}

	@Test
	@DisplayName("신고 처리는 토큰이 없으면 401, USER 토큰이면 403이다")
	void handleRequiresAdmin() throws Exception {
		long reportId = submitUserReport(reporter, target, "OTHER", null);

		mockMvc.perform(json(patch(ADMIN_REPORTS + "/" + reportId), handleBody("RESOLVED", "처리", null)))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
		mockMvc.perform(handle(reporter, reportId, handleBody("RESOLVED", "처리", null)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("FORBIDDEN"));

		assertReportUnchanged(reportId);
	}

	@Test
	@DisplayName("두 관리자가 같은 신고를 동시에 처리하면 하나만 성공하고 나머지는 409다")
	void concurrentHandlingIsSerialized() throws Exception {
		long reportId = submitUserReport(reporter, target, "OTHER", null);
		Member otherAdmin = admin("admin2@example.com", "관리자2");
		CyclicBarrier start = new CyclicBarrier(2);

		CompletableFuture<Integer> first = CompletableFuture.supplyAsync(
				() -> handleConcurrently(start, admin, reportId, "RESOLVED"));
		CompletableFuture<Integer> second = CompletableFuture.supplyAsync(
				() -> handleConcurrently(start, otherAdmin, reportId, "REJECTED"));

		assertThat(List.of(first.join(), second.join())).containsExactlyInAnyOrder(200, 409);
		assertThat(audits()).hasSize(1);
		assertThat(notifications()).hasSize(1);
	}

	// --- helpers ---

	private int handleConcurrently(CyclicBarrier start, Member handler, long reportId, String status) {
		try {
			start.await();
			return mockMvc.perform(handle(handler, reportId, handleBody(status, "동시 처리", null)))
					.andReturn().getResponse().getStatus();
		}
		catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	private long submitUserReport(Member from, Member about, String reasonCode, String detail) throws Exception {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("targetType", "USER");
		body.put("targetId", about.userId());
		body.put("reasonCode", reasonCode);
		if (detail != null) {
			body.put("detail", detail);
		}
		MvcResult result = mockMvc.perform(json(post("/api/v1/reports"), body).header("Authorization", from.bearer()))
				.andExpect(status().isCreated())
				.andReturn();
		return readJson(result).get("reportId").asLong();
	}

	private long insertReport(long reporterId, String targetType, long targetId, String reasonCode, String status) {
		return jdbcTemplate.queryForObject("INSERT INTO reports (reporter_id, target_type, target_id, reason_code, "
				+ "status) VALUES (?, ?, ?, ?, ?) RETURNING report_id", Long.class, reporterId, targetType, targetId,
				reasonCode, status);
	}

	private MockHttpServletRequestBuilder handle(Member handler, long reportId, Map<String, Object> body) {
		return json(patch(ADMIN_REPORTS + "/" + reportId), body).header("Authorization", handler.bearer());
	}

	private static Map<String, Object> handleBody(String status, String resolution, String action) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("status", status);
		if (resolution != null) {
			body.put("resolution", resolution);
		}
		if (action != null) {
			body.put("action", action);
		}
		return body;
	}

	private List<Long> listIds(Map<String, String> params) throws Exception {
		MockHttpServletRequestBuilder request = get(ADMIN_REPORTS).header("Authorization", admin.bearer());
		params.forEach(request::param);
		JsonNode page = readJson(mockMvc.perform(request).andExpect(status().isOk()).andReturn());
		List<Long> ids = new ArrayList<>();
		page.get("items").forEach(item -> ids.add(item.get("reportId").asLong()));
		return ids;
	}

	private Map<String, Object> reportRow(long reportId) {
		return jdbcTemplate.queryForMap("SELECT status, handled_by, handled_at, resolution FROM reports "
				+ "WHERE report_id = ?", reportId);
	}

	/** 신고가 접수 상태 그대로이고 처리 흔적(감사·알림)이 없다 */
	private void assertReportUnchanged(long reportId) {
		assertThat(reportRow(reportId)).containsEntry("status", "RECEIVED").containsEntry("handled_by", null)
				.containsEntry("handled_at", null).containsEntry("resolution", null);
		assertThat(audits()).isEmpty();
		assertThat(notifications()).isEmpty();
	}

	/** 탈퇴·미존재 관리자 토큰은 목록·처리 모두 401이고 신고·대상 회원이 그대로다 */
	private void assertAdminUnauthenticated(Member caller, long reportId) throws Exception {
		mockMvc.perform(get(ADMIN_REPORTS).header("Authorization", caller.bearer()))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
		mockMvc.perform(handle(caller, reportId, handleBody("RESOLVED", RESOLUTION, "SUSPEND_USER")))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

		assertReportUnchanged(reportId);
		assertThat(userStatus(target.userId())).isEqualTo("ACTIVE");
	}

	/** 가입·로그인 감사(AUTH_*)는 뺀다 */
	private List<Map<String, Object>> audits() {
		return jdbcTemplate.queryForList("SELECT action, actor_id, target_type, target_id, result, detail::text AS "
				+ "detail FROM audit_logs WHERE action NOT LIKE 'AUTH%' ORDER BY audit_log_id");
	}

	private JsonNode detail(Map<String, Object> audit) {
		return objectMapper.readTree((String) audit.get("detail"));
	}

	private List<Map<String, Object>> notifications() {
		return jdbcTemplate.queryForList("SELECT user_id, type, title, body, target_type, target_id, read_at "
				+ "FROM notifications ORDER BY notification_id");
	}

	private String userStatus(long userId) {
		return jdbcTemplate.queryForObject("SELECT status FROM users WHERE user_id = ?", String.class, userId);
	}

	private Instant suspendedUntil(long userId) {
		Timestamp until = jdbcTemplate.queryForObject("SELECT suspended_until FROM users WHERE user_id = ?",
				Timestamp.class, userId);
		return until == null ? null : until.toInstant();
	}

	private Set<String> refreshKeys(long userId) {
		return redisTemplate.keys("reused:auth:refresh:" + userId + ":*");
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
