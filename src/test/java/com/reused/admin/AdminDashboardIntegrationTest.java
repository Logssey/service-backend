package com.reused.admin;

import static com.reused.support.AdminTestClient.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import tools.jackson.databind.ObjectMapper;

import com.reused.TestcontainersConfiguration;
import com.reused.admin.api.ListingStatsPort;
import com.reused.admin.api.MarketplaceMetrics;
import com.reused.admin.api.MarketplaceMetricsPort;
import com.reused.auth.client.OAuthProviderClient;
import com.reused.auth.mail.AuthMailSender;
import com.reused.support.AdminTestClient;
import com.reused.support.AdminTestClient.Member;

/**
 * GET /api/v1/admin/dashboard. 회원·신고 수는 B 테이블에서 세고 게시글·거래 수는 A 포트(대역) 값을 그대로 싣는다.
 * 포트가 없을 때는 {@code MarketplacePortsAbsentIntegrationTest}, 권한은 {@code AdminEndpointAccessIntegrationTest}.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AdminDashboardIntegrationTest {

	private static final String PATH = "/api/v1/admin/dashboard";

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
		jdbcTemplate.update("DELETE FROM audit_logs");
		given(marketplaceMetricsPort.currentMetrics()).willReturn(new MarketplaceMetrics(3400, 2100, 890));
	}

	@Test
	@DisplayName("회원 수(탈퇴 포함 전체·활성·정지), A 제공 게시글·거래 수, 미처리 신고 수를 모두 숫자로 준다")
	void aggregatesAllMetrics() throws Exception {
		long active = client.insertUser("활성회원");
		long timed = client.insertUser("기간정지");
		long indefinite = client.insertUser("무기한정지");
		long withdrawn = client.insertUser("탈퇴회원");
		client.suspend(timed, "now() + interval '3 days'");
		client.suspend(indefinite, "NULL");
		client.withdraw(withdrawn);
		client.insertReport(active, "USER", timed, "OTHER", "RECEIVED");
		client.insertReport(active, "LISTING", 10, "SPAM", "RECEIVED");
		client.insertReport(active, "MESSAGE", 20, "ABUSE", "IN_REVIEW");
		client.insertReport(active, "USER", indefinite, "OTHER", "RESOLVED");
		client.insertReport(active, "LISTING", 11, "SPAM", "REJECTED");

		dashboard()
				.andExpect(status().isOk())
				.andExpect(content().json("""
						{
						  "totalUsers": 5,
						  "activeUsers": 2,
						  "suspendedUsers": 2,
						  "totalListings": 3400,
						  "onSaleListings": 2100,
						  "completedTrades": 890,
						  "pendingReports": 3
						}""", JsonCompareMode.STRICT));
	}

	@Test
	@DisplayName("회원 수는 DB 상태값 기준이다. 기간이 지났지만 아직 해제되지 않은 정지는 정지로 센다(만료 해제 작업이 되돌린다)")
	void countsStoredStatus() throws Exception {
		long expired = client.insertUser("만료정지");
		client.suspend(expired, "now() - interval '1 minute'");

		dashboard()
				.andExpect(jsonPath("$.activeUsers").value(1))
				.andExpect(jsonPath("$.suspendedUsers").value(1));
	}

	@Test
	@DisplayName("탈퇴 시각만 기록된 비정상 행은 활성으로 세지 않는다")
	void withdrawnAtOnlyIsNotActive() throws Exception {
		long broken = client.insertUser("시각만기록");
		jdbcTemplate.update("UPDATE users SET withdrawn_at = now() WHERE user_id = ?", broken);

		dashboard()
				.andExpect(jsonPath("$.totalUsers").value(2))
				.andExpect(jsonPath("$.activeUsers").value(1))
				.andExpect(jsonPath("$.suspendedUsers").value(0));
	}

	@Test
	@DisplayName("신고가 없으면 0이고, A 포트는 요청마다 한 번 부른다. 조회는 감사 기록을 남기지 않는다")
	void emptyStateAndNoSideEffects() throws Exception {
		dashboard()
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalUsers").value(1))
				.andExpect(jsonPath("$.activeUsers").value(1))
				.andExpect(jsonPath("$.pendingReports").value(0));

		verify(marketplaceMetricsPort, times(1)).currentMetrics();
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs", Long.class)).isZero();
	}

	private ResultActions dashboard() throws Exception {
		return mockMvc.perform(get(PATH).header("Authorization", bearer(admin)));
	}

}
