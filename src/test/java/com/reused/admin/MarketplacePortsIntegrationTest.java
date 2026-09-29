package com.reused.admin;

import static com.reused.support.AdminTestClient.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.ObjectMapper;

import com.reused.TestcontainersConfiguration;
import com.reused.admin.api.ListingStatsPort;
import com.reused.admin.api.MarketplaceMetrics;
import com.reused.admin.api.MarketplaceMetricsPort;
import com.reused.auth.client.OAuthProviderClient;
import com.reused.auth.mail.AuthMailSender;
import com.reused.support.AdminTestClient;
import com.reused.support.AdminTestClient.Member;

/** Real database-backed marketplace counts, including the administrator HTTP responses. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class MarketplacePortsIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private StringRedisTemplate redisTemplate;

	@Autowired
	private ListingStatsPort listingStatsPort;

	@Autowired
	private MarketplaceMetricsPort marketplaceMetricsPort;

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
	void countsLiveListingsAndCompletedTradesFromTemporaryPostgres() throws Exception {
		long sellerA = client.insertUser("판매자A");
		long sellerB = client.insertUser("판매자B");
		long buyer = client.insertUser("구매자");
		insertListing(sellerA, "판매 중", "ON_SALE");
		insertListing(sellerA, "관리자 숨김", "HIDDEN");
		long completed = insertListing(sellerB, "거래 완료", "COMPLETED");
		long deleted = insertListing(sellerB, "삭제됨", "ON_SALE");
		jdbcTemplate.update("UPDATE listings SET deleted_at = now(), deleted_by = ? WHERE listing_id = ?",
				sellerB, deleted);
		jdbcTemplate.update("INSERT INTO trades (listing_id, seller_id, buyer_id, status, completed_at) "
				+ "VALUES (?, ?, ?, 'COMPLETED', now())", completed, sellerB, buyer);

		assertThat(listingStatsPort.countBySellers(List.of(sellerA, sellerB, buyer)))
				.containsEntry(sellerA, 2L)
				.containsEntry(sellerB, 1L)
				.doesNotContainKey(buyer);
		assertThat(listingStatsPort.countBySellers(List.of())).isEmpty();
		assertThat(marketplaceMetricsPort.currentMetrics()).isEqualTo(new MarketplaceMetrics(3, 1, 1));

		mockMvc.perform(get("/api/v1/admin/users").header("Authorization", bearer(admin)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].userId").value(buyer))
				.andExpect(jsonPath("$.items[0].listingCount").value(0))
				.andExpect(jsonPath("$.items[1].userId").value(sellerB))
				.andExpect(jsonPath("$.items[1].listingCount").value(1))
				.andExpect(jsonPath("$.items[2].userId").value(sellerA))
				.andExpect(jsonPath("$.items[2].listingCount").value(2));

		mockMvc.perform(get("/api/v1/admin/dashboard").header("Authorization", bearer(admin)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalListings").value(3))
				.andExpect(jsonPath("$.onSaleListings").value(1))
				.andExpect(jsonPath("$.completedTrades").value(1));
	}

	private long insertListing(long sellerId, String title, String status) {
		return jdbcTemplate.queryForObject("""
				INSERT INTO listings (seller_id, category_id, title, description, price, item_condition,
				                      trade_method, status)
				VALUES (?, 1, ?, '테스트 상품', 1000, 'USED', 'DIRECT', ?)
				RETURNING listing_id""", Long.class, sellerId, title, status);
	}

}
