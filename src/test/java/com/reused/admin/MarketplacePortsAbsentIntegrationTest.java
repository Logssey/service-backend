package com.reused.admin;

import static com.reused.support.AdminTestClient.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.ObjectMapper;

import com.reused.TestcontainersConfiguration;
import com.reused.admin.api.ListingStatsPort;
import com.reused.admin.api.MarketplaceMetricsPort;
import com.reused.auth.client.OAuthProviderClient;
import com.reused.auth.mail.AuthMailSender;
import com.reused.support.AdminTestClient;
import com.reused.support.AdminTestClient.Member;

/**
 * A의 포트 구현이 없는 지금 상태. 조회·집계 포트가 없으면 "데이터 없음"으로 보고 0을 낸다(contracts 판단 원칙 4).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class MarketplacePortsAbsentIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private StringRedisTemplate redisTemplate;

	@Autowired
	private ApplicationContext context;

	@MockitoBean
	private AuthMailSender mailSender;

	@MockitoBean
	private OAuthProviderClient kakaoOAuthClient;

	private AdminTestClient client;
	private Member admin;

	@BeforeEach
	void resetState() throws Exception {
		client = new AdminTestClient(mockMvc, objectMapper, jdbcTemplate, redisTemplate);
		client.reset();
		admin = client.signupAdmin("admin@example.com", "관리자");
	}

	@Test
	@DisplayName("A 포트 구현 빈이 없다")
	void portsAreAbsent() {
		assertThat(context.getBeansOfType(ListingStatsPort.class)).isEmpty();
		assertThat(context.getBeansOfType(MarketplaceMetricsPort.class)).isEmpty();
	}

	@Test
	@DisplayName("회원 목록의 게시글 수는 0이고 피신고 수는 B 테이블에서 그대로 센다")
	void listingCountIsZeroWithoutPort() throws Exception {
		long member = client.insertUser("회원");
		client.insertReport(admin.userId(), "USER", member, "OTHER", "RECEIVED");

		mockMvc.perform(get("/api/v1/admin/users").header("Authorization", bearer(admin)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].userId").value(member))
				.andExpect(jsonPath("$.items[0].listingCount").value(0))
				.andExpect(jsonPath("$.items[0].reportedCount").value(1))
				.andExpect(jsonPath("$.items[1].listingCount").value(0));
	}

	@Test
	@DisplayName("대시보드의 게시글·거래 수는 0이고 회원·신고 수는 그대로 센다")
	void marketplaceMetricsAreZeroWithoutPort() throws Exception {
		long member = client.insertUser("회원");
		client.insertReport(admin.userId(), "USER", member, "OTHER", "IN_REVIEW");

		mockMvc.perform(get("/api/v1/admin/dashboard").header("Authorization", bearer(admin)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalUsers").value(2))
				.andExpect(jsonPath("$.activeUsers").value(2))
				.andExpect(jsonPath("$.suspendedUsers").value(0))
				.andExpect(jsonPath("$.totalListings").value(0))
				.andExpect(jsonPath("$.onSaleListings").value(0))
				.andExpect(jsonPath("$.completedTrades").value(0))
				.andExpect(jsonPath("$.pendingReports").value(1));
	}

}
