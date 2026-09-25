package com.reused.trade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
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
import com.reused.user.entity.UserRole;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class TradeIntegrationTest {

	private static final String TRADES = "/api/v1/trades";
	private static final Instant BASE_TIME = Instant.parse("2026-09-23T01:00:00Z");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JwtTokenProvider tokenProvider;

	@MockitoBean
	private OAuthProviderClient kakaoOAuthClient;

	@MockitoBean
	private AuthMailSender mailSender;

	@BeforeEach
	void resetState() {
		jdbcTemplate.execute("TRUNCATE users RESTART IDENTITY CASCADE");
	}

	@Test
	@DisplayName("거래 요청은 REQUESTED 거래와 최초 상태 이력을 함께 생성한다")
	void requestCreatesTradeAndInitialHistory() throws Exception {
		Long sellerId = insertUser("판매자", UserRole.USER);
		Long buyerId = insertUser("구매자", UserRole.USER);
		Long listingId = insertListing(sellerId, "요청할 상품", "ON_SALE", BASE_TIME);

		MvcResult result = mockMvc.perform(json(post(TRADES), Map.of("listingId", listingId))
					.header("Authorization", bearer(buyerId, UserRole.USER)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.tradeId").isNumber())
				.andExpect(jsonPath("$.status").value("REQUESTED"))
				.andReturn();

		Long tradeId = response(result).get("tradeId").asLong();
		Map<String, Object> trade = jdbcTemplate.queryForMap(
				"SELECT listing_id, seller_id, buyer_id, status, requested_at, version FROM trades WHERE trade_id = ?",
				tradeId);
		assertThat(((Number) trade.get("listing_id")).longValue()).isEqualTo(listingId);
		assertThat(((Number) trade.get("seller_id")).longValue()).isEqualTo(sellerId);
		assertThat(((Number) trade.get("buyer_id")).longValue()).isEqualTo(buyerId);
		assertThat(trade.get("status")).isEqualTo("REQUESTED");
		assertThat(trade.get("requested_at")).isNotNull();
		assertThat(trade.get("version")).isEqualTo(0);

		Map<String, Object> history = jdbcTemplate.queryForMap(
				"SELECT before_status, after_status, changed_by, reason, created_at "
						+ "FROM trade_status_histories WHERE trade_id = ?",
				tradeId);
		assertThat(history.get("before_status")).isNull();
		assertThat(history.get("after_status")).isEqualTo("REQUESTED");
		assertThat(((Number) history.get("changed_by")).longValue()).isEqualTo(buyerId);
		assertThat(history.get("reason")).isNull();
		assertThat(history.get("created_at")).isNotNull();

		mockMvc.perform(json(post(TRADES), Map.of("listingId", listingId))
					.header("Authorization", bearer(buyerId, UserRole.USER)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("CONFLICT"));
		mockMvc.perform(json(post(TRADES), Map.of("listingId", listingId))
					.header("Authorization", bearer(sellerId, UserRole.USER)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"));
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM trades", Long.class)).isEqualTo(1);
	}

	@Test
	@DisplayName("거래 명령은 USER와 당사자 권한을 확인하고 요청과 승인은 정지 회원에게 차단한다")
	void commandsEnforceRolePartyAndSuspension() throws Exception {
		Long sellerId = insertUser("판매자", UserRole.USER);
		Long buyerId = insertUser("구매자", UserRole.USER);
		Long outsiderId = insertUser("제삼자", UserRole.USER);
		Long suspendedBuyerId = insertUser("정지구매자", UserRole.USER);
		Long adminId = insertUser("관리자", UserRole.ADMIN);
		Long listingId = insertListing(sellerId, "권한 확인 상품", "ON_SALE", BASE_TIME);
		String suspendedBuyerToken = bearer(suspendedBuyerId, UserRole.USER);
		jdbcTemplate.update("UPDATE users SET status = 'SUSPENDED' WHERE user_id = ?", suspendedBuyerId);

		mockMvc.perform(json(post(TRADES), Map.of("listingId", listingId)))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
		mockMvc.perform(json(post(TRADES), Map.of("listingId", listingId))
					.header("Authorization", bearer(adminId, UserRole.ADMIN)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("FORBIDDEN"));
		mockMvc.perform(json(post(TRADES), Map.of("listingId", listingId))
					.header("Authorization", suspendedBuyerToken))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("USER_SUSPENDED"));

		Long tradeId = insertTrade(listingId, sellerId, buyerId, "REQUESTED", BASE_TIME);
		insertHistory(tradeId, null, "REQUESTED", buyerId, null, BASE_TIME);
		mockMvc.perform(post(TRADES + "/{tradeId}/accept", tradeId)
					.header("Authorization", bearer(outsiderId, UserRole.USER)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("FORBIDDEN"));

		String sellerToken = bearer(sellerId, UserRole.USER);
		jdbcTemplate.update("UPDATE users SET status = 'SUSPENDED' WHERE user_id = ?", sellerId);
		mockMvc.perform(post(TRADES + "/{tradeId}/accept", tradeId)
					.header("Authorization", sellerToken))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("USER_SUSPENDED"));
		assertThat(jdbcTemplate.queryForObject(
				"SELECT status FROM trades WHERE trade_id = ?", String.class, tradeId)).isEqualTo("REQUESTED");
		assertThat(jdbcTemplate.queryForObject(
				"SELECT status FROM listings WHERE listing_id = ?", String.class, listingId)).isEqualTo("ON_SALE");
	}

	@Test
	@DisplayName("판매자는 요청을 승인하거나 사유와 함께 거절할 수 있다")
	void sellerAcceptsOrRejectsRequestedTrade() throws Exception {
		Long sellerId = insertUser("판매자", UserRole.USER);
		Long firstBuyerId = insertUser("첫구매자", UserRole.USER);
		Long secondBuyerId = insertUser("둘구매자", UserRole.USER);
		Long acceptedListingId = insertListing(sellerId, "승인할 상품", "ON_SALE", BASE_TIME);
		Long rejectedListingId = insertListing(sellerId, "거절할 상품", "ON_SALE", BASE_TIME.plusSeconds(1));
		Long acceptedTradeId = insertTrade(
				acceptedListingId, sellerId, firstBuyerId, "REQUESTED", BASE_TIME);
		Long rejectedTradeId = insertTrade(
				rejectedListingId, sellerId, secondBuyerId, "REQUESTED", BASE_TIME.plusSeconds(1));
		insertHistory(acceptedTradeId, null, "REQUESTED", firstBuyerId, null, BASE_TIME);
		insertHistory(rejectedTradeId, null, "REQUESTED", secondBuyerId, null, BASE_TIME.plusSeconds(1));
		String authorization = bearer(sellerId, UserRole.USER);

		mockMvc.perform(post(TRADES + "/{tradeId}/accept", acceptedTradeId)
					.header("Authorization", authorization))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tradeId").value(acceptedTradeId))
				.andExpect(jsonPath("$.status").value("ACCEPTED"))
				.andExpect(jsonPath("$.changedAt").isNotEmpty());
		assertThat(jdbcTemplate.queryForObject(
				"SELECT status FROM listings WHERE listing_id = ?", String.class, acceptedListingId))
				.isEqualTo("RESERVED");
		assertThat(jdbcTemplate.queryForObject(
				"SELECT accepted_at FROM trades WHERE trade_id = ?", Timestamp.class, acceptedTradeId))
				.isNotNull();

		mockMvc.perform(json(post(TRADES + "/{tradeId}/reject", rejectedTradeId),
					Map.of("reason", "다른 구매자와 진행합니다"))
					.header("Authorization", authorization))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("REJECTED"));
		Map<String, Object> rejected = jdbcTemplate.queryForMap(
				"SELECT status, closed_at, closed_by FROM trades WHERE trade_id = ?", rejectedTradeId);
		assertThat(rejected.get("status")).isEqualTo("REJECTED");
		assertThat(rejected.get("closed_at")).isNotNull();
		assertThat(((Number) rejected.get("closed_by")).longValue()).isEqualTo(sellerId);
		assertThat(jdbcTemplate.queryForObject(
				"SELECT status FROM listings WHERE listing_id = ?", String.class, rejectedListingId))
				.isEqualTo("ON_SALE");
		assertThat(jdbcTemplate.queryForObject(
				"SELECT reason FROM trade_status_histories WHERE trade_id = ? AND after_status = 'REJECTED'",
				String.class, rejectedTradeId)).isEqualTo("다른 구매자와 진행합니다");
	}

	@Test
	@DisplayName("REQUESTED 거래는 구매자만 취소할 수 있고 게시글 상태는 유지된다")
	void buyerCancelsRequestedTrade() throws Exception {
		Long sellerId = insertUser("판매자", UserRole.USER);
		Long buyerId = insertUser("구매자", UserRole.USER);
		Long listingId = insertListing(sellerId, "취소할 요청", "ON_SALE", BASE_TIME);
		Long tradeId = insertTrade(listingId, sellerId, buyerId, "REQUESTED", BASE_TIME);
		insertHistory(tradeId, null, "REQUESTED", buyerId, null, BASE_TIME);

		mockMvc.perform(json(post(TRADES + "/{tradeId}/cancel", tradeId), Map.of("reason", "구매 포기"))
					.header("Authorization", bearer(sellerId, UserRole.USER)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("CONFLICT"));
		mockMvc.perform(json(post(TRADES + "/{tradeId}/cancel", tradeId), Map.of("reason", "  구매 포기  "))
					.header("Authorization", bearer(buyerId, UserRole.USER)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("CANCELED"));

		Map<String, Object> trade = jdbcTemplate.queryForMap(
				"SELECT status, closed_by, closed_at FROM trades WHERE trade_id = ?", tradeId);
		assertThat(trade.get("status")).isEqualTo("CANCELED");
		assertThat(((Number) trade.get("closed_by")).longValue()).isEqualTo(buyerId);
		assertThat(trade.get("closed_at")).isNotNull();
		assertThat(jdbcTemplate.queryForObject(
				"SELECT status FROM listings WHERE listing_id = ?", String.class, listingId)).isEqualTo("ON_SALE");
		assertThat(jdbcTemplate.queryForObject(
				"SELECT reason FROM trade_status_histories WHERE trade_id = ? AND after_status = 'CANCELED'",
				String.class, tradeId)).isEqualTo("구매 포기");
	}

	@Test
	@DisplayName("ACCEPTED 거래를 당사자가 취소하면 게시글은 다시 판매 중이 된다")
	void partyCancelsAcceptedTradeAndReopensListing() throws Exception {
		Long sellerId = insertUser("판매자", UserRole.USER);
		Long buyerId = insertUser("구매자", UserRole.USER);
		Long listingId = insertListing(sellerId, "예약 상품", "RESERVED", BASE_TIME);
		Long tradeId = insertTrade(listingId, sellerId, buyerId, "ACCEPTED", BASE_TIME);
		insertHistory(tradeId, null, "REQUESTED", buyerId, null, BASE_TIME);
		insertHistory(tradeId, "REQUESTED", "ACCEPTED", sellerId, null, BASE_TIME.plusSeconds(1));

		mockMvc.perform(json(post(TRADES + "/{tradeId}/cancel", tradeId), Map.of("reason", "거래 일정 불일치"))
					.header("Authorization", bearer(sellerId, UserRole.USER)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("CANCELED"));

		assertThat(jdbcTemplate.queryForObject(
				"SELECT status FROM listings WHERE listing_id = ?", String.class, listingId)).isEqualTo("ON_SALE");
		assertThat(jdbcTemplate.queryForObject(
				"SELECT status FROM trades WHERE trade_id = ?", String.class, tradeId)).isEqualTo("CANCELED");
		assertThat(jdbcTemplate.queryForObject(
				"SELECT count(*) FROM trade_status_histories WHERE trade_id = ?", Long.class, tradeId))
				.isEqualTo(3);
	}

	@Test
	@DisplayName("ACCEPTED 거래는 구매자만 완료할 수 있고 게시글도 함께 완료된다")
	void onlyBuyerCompletesAcceptedTrade() throws Exception {
		Long sellerId = insertUser("판매자", UserRole.USER);
		Long buyerId = insertUser("구매자", UserRole.USER);
		Long listingId = insertListing(sellerId, "완료할 상품", "RESERVED", BASE_TIME);
		Long tradeId = insertTrade(listingId, sellerId, buyerId, "ACCEPTED", BASE_TIME);
		insertHistory(tradeId, null, "REQUESTED", buyerId, null, BASE_TIME);
		insertHistory(tradeId, "REQUESTED", "ACCEPTED", sellerId, null, BASE_TIME.plusSeconds(1));

		mockMvc.perform(post(TRADES + "/{tradeId}/complete", tradeId)
					.header("Authorization", bearer(sellerId, UserRole.USER)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("FORBIDDEN"));
		mockMvc.perform(post(TRADES + "/{tradeId}/complete", tradeId)
					.header("Authorization", bearer(buyerId, UserRole.USER)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("COMPLETED"));

		Map<String, Object> trade = jdbcTemplate.queryForMap(
				"SELECT status, completed_at FROM trades WHERE trade_id = ?", tradeId);
		assertThat(trade.get("status")).isEqualTo("COMPLETED");
		assertThat(trade.get("completed_at")).isNotNull();
		assertThat(jdbcTemplate.queryForObject(
				"SELECT status FROM listings WHERE listing_id = ?", String.class, listingId))
				.isEqualTo("COMPLETED");
		assertThat(jdbcTemplate.queryForObject(
				"SELECT count(*) FROM trade_status_histories WHERE trade_id = ? AND before_status = 'ACCEPTED' "
						+ "AND after_status = 'COMPLETED' AND changed_by = ?",
				Long.class, tradeId, buyerId)).isOne();
	}

	@Test
	@DisplayName("거래 목록은 역할과 상태로 필터링하고 동률 커서에서도 중복 없이 이어진다")
	void listFiltersByRoleAndStatusWithStableCursor() throws Exception {
		Long memberId = insertUser("조회회원", UserRole.USER);
		Long sellerId = insertUser("상대판매자", UserRole.USER);
		Long otherBuyerId = insertUser("상대구매자", UserRole.USER);
		Long firstListingId = insertListing(sellerId, "첫 상품", "ON_SALE", BASE_TIME);
		Long secondListingId = insertListing(sellerId, "둘 상품", "ON_SALE", BASE_TIME);
		Long thirdListingId = insertListing(sellerId, "셋 상품", "ON_SALE", BASE_TIME);
		Long sellerListingId = insertListing(memberId, "판매 상품", "RESERVED", BASE_TIME);
		Long firstTradeId = insertTrade(firstListingId, sellerId, memberId, "REQUESTED", BASE_TIME);
		Long secondTradeId = insertTrade(secondListingId, sellerId, memberId, "REQUESTED", BASE_TIME);
		Long thirdTradeId = insertTrade(thirdListingId, sellerId, memberId, "REQUESTED", BASE_TIME);
		Long sellerTradeId = insertTrade(
				sellerListingId, memberId, otherBuyerId, "ACCEPTED", BASE_TIME.plusSeconds(1));
		String authorization = bearer(memberId, UserRole.USER);

		MvcResult firstPageResult = mockMvc.perform(get(TRADES)
					.param("role", "buyer")
					.param("status", "REQUESTED")
					.param("size", "2")
					.header("Authorization", authorization))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(2))
				.andExpect(jsonPath("$.items[0].tradeId").value(thirdTradeId))
				.andExpect(jsonPath("$.items[0].myRole").value("BUYER"))
				.andExpect(jsonPath("$.items[0].counterparty.userId").value(sellerId))
				.andExpect(jsonPath("$.items[1].tradeId").value(secondTradeId))
				.andExpect(jsonPath("$.hasNext").value(true))
				.andExpect(jsonPath("$.nextCursor").isNotEmpty())
				.andReturn();
		String cursor = response(firstPageResult).get("nextCursor").asString();

		mockMvc.perform(get(TRADES)
					.param("role", "buyer")
					.param("status", "REQUESTED")
					.param("size", "2")
					.param("cursor", cursor)
					.header("Authorization", authorization))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].tradeId").value(firstTradeId))
				.andExpect(jsonPath("$.hasNext").value(false))
				.andExpect(jsonPath("$.nextCursor").doesNotExist());

		mockMvc.perform(get(TRADES)
					.param("role", "seller")
					.param("status", "ACCEPTED")
					.header("Authorization", authorization))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].tradeId").value(sellerTradeId))
				.andExpect(jsonPath("$.items[0].myRole").value("SELLER"))
				.andExpect(jsonPath("$.items[0].counterparty.userId").value(otherBuyerId));
	}

	@Test
	@DisplayName("거래 상세는 당사자에게만 양측 정보와 시간순 상태 이력을 제공한다")
	void detailContainsPartiesAndHistoriesOnlyForParty() throws Exception {
		Long sellerId = insertUser("판매자", UserRole.USER);
		Long buyerId = insertUser("구매자", UserRole.USER);
		Long outsiderId = insertUser("제삼자", UserRole.USER);
		Long listingId = insertListing(sellerId, "상세 상품", "RESERVED", BASE_TIME);
		Long tradeId = insertTrade(listingId, sellerId, buyerId, "ACCEPTED", BASE_TIME);
		insertHistory(tradeId, null, "REQUESTED", buyerId, null, BASE_TIME);
		insertHistory(tradeId, "REQUESTED", "ACCEPTED", sellerId, null, BASE_TIME.plusSeconds(1));

		mockMvc.perform(get(TRADES + "/{tradeId}", tradeId)
					.header("Authorization", bearer(buyerId, UserRole.USER)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tradeId").value(tradeId))
				.andExpect(jsonPath("$.status").value("ACCEPTED"))
				.andExpect(jsonPath("$.listing.listingId").value(listingId))
				.andExpect(jsonPath("$.listing.title").value("상세 상품"))
				.andExpect(jsonPath("$.seller.userId").value(sellerId))
				.andExpect(jsonPath("$.seller.nickname").value("판매자"))
				.andExpect(jsonPath("$.buyer.userId").value(buyerId))
				.andExpect(jsonPath("$.buyer.nickname").value("구매자"))
				.andExpect(jsonPath("$.myRole").value("BUYER"))
				.andExpect(jsonPath("$.histories.length()").value(2))
				.andExpect(jsonPath("$.histories[0].status").value("REQUESTED"))
				.andExpect(jsonPath("$.histories[1].status").value("ACCEPTED"));

		mockMvc.perform(get(TRADES + "/{tradeId}", tradeId)
					.header("Authorization", bearer(sellerId, UserRole.USER)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.myRole").value("SELLER"));
		mockMvc.perform(get(TRADES + "/{tradeId}", tradeId)
					.header("Authorization", bearer(outsiderId, UserRole.USER)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("FORBIDDEN"));
	}

	@Test
	@DisplayName("같은 게시글의 두 요청을 동시에 승인해도 하나만 성공한다")
	void concurrentAcceptAllowsOnlyOneTrade() throws Exception {
		Long sellerId = insertUser("판매자", UserRole.USER);
		Long firstBuyerId = insertUser("첫구매자", UserRole.USER);
		Long secondBuyerId = insertUser("둘구매자", UserRole.USER);
		Long listingId = insertListing(sellerId, "동시 승인 상품", "ON_SALE", BASE_TIME);
		Long firstTradeId = insertTrade(listingId, sellerId, firstBuyerId, "REQUESTED", BASE_TIME);
		Long secondTradeId = insertTrade(listingId, sellerId, secondBuyerId, "REQUESTED", BASE_TIME.plusMillis(1));
		insertHistory(firstTradeId, null, "REQUESTED", firstBuyerId, null, BASE_TIME);
		insertHistory(secondTradeId, null, "REQUESTED", secondBuyerId, null, BASE_TIME.plusMillis(1));
		String authorization = bearer(sellerId, UserRole.USER);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);

		try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
			Future<Integer> first = executor.submit(() -> acceptStatus(
					firstTradeId, authorization, ready, start));
			Future<Integer> second = executor.submit(() -> acceptStatus(
					secondTradeId, authorization, ready, start));
			ready.await();
			start.countDown();

			assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(200, 409);
		}

		assertThat(jdbcTemplate.queryForObject(
				"SELECT count(*) FROM trades WHERE listing_id = ? AND status = 'ACCEPTED'",
				Long.class, listingId)).isOne();
		assertThat(jdbcTemplate.queryForObject(
				"SELECT count(*) FROM trades WHERE listing_id = ? AND status = 'REQUESTED'",
				Long.class, listingId)).isOne();
		assertThat(jdbcTemplate.queryForObject(
				"SELECT status FROM listings WHERE listing_id = ?", String.class, listingId))
				.isEqualTo("RESERVED");
		assertThat(jdbcTemplate.queryForObject(
				"SELECT count(*) FROM trade_status_histories h JOIN trades t ON t.trade_id = h.trade_id "
						+ "WHERE t.listing_id = ? AND h.after_status = 'ACCEPTED'",
				Long.class, listingId)).isOne();
	}

	private int acceptStatus(Long tradeId, String authorization, CountDownLatch ready,
			CountDownLatch start) throws Exception {
		ready.countDown();
		start.await();
		return mockMvc.perform(post(TRADES + "/{tradeId}/accept", tradeId)
					.header("Authorization", authorization))
				.andReturn()
				.getResponse()
				.getStatus();
	}

	private Long insertUser(String nickname, UserRole role) {
		return jdbcTemplate.queryForObject(
				"INSERT INTO users (nickname, role, terms_agreed_at) VALUES (?, ?, now()) RETURNING user_id",
				Long.class, nickname, role.name());
	}

	private Long insertListing(Long sellerId, String title, String status, Instant createdAt) {
		Long categoryId = jdbcTemplate.queryForObject(
				"SELECT category_id FROM categories WHERE name = '디지털기기'", Long.class);
		return jdbcTemplate.queryForObject(
				"INSERT INTO listings (seller_id, category_id, title, description, price, item_condition, "
						+ "trade_method, status, created_at) "
						+ "VALUES (?, ?, ?, ?, 10000, 'LIKE_NEW', 'BOTH', ?, ?) RETURNING listing_id",
				Long.class, sellerId, categoryId, title, title + " 설명", status, Timestamp.from(createdAt));
	}

	private Long insertTrade(Long listingId, Long sellerId, Long buyerId, String status,
			Instant requestedAt) {
		Timestamp acceptedAt = "ACCEPTED".equals(status) ? Timestamp.from(requestedAt.plusSeconds(1)) : null;
		Timestamp completedAt = "COMPLETED".equals(status) ? Timestamp.from(requestedAt.plusSeconds(2)) : null;
		return jdbcTemplate.queryForObject(
				"INSERT INTO trades (listing_id, seller_id, buyer_id, status, requested_at, accepted_at, completed_at) "
						+ "VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING trade_id",
				Long.class, listingId, sellerId, buyerId, status, Timestamp.from(requestedAt),
				acceptedAt, completedAt);
	}

	private void insertHistory(Long tradeId, String beforeStatus, String afterStatus, Long changedBy,
			String reason, Instant createdAt) {
		jdbcTemplate.update(
				"INSERT INTO trade_status_histories "
						+ "(trade_id, before_status, after_status, changed_by, reason, created_at) "
						+ "VALUES (?, ?, ?, ?, ?, ?)",
				tradeId, beforeStatus, afterStatus, changedBy, reason, Timestamp.from(createdAt));
	}

	private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder,
			Map<String, ?> body) throws Exception {
		return builder.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(body));
	}

	private String bearer(Long userId, UserRole role) {
		return "Bearer " + tokenProvider.issueAccessToken(userId, role);
	}

	private JsonNode response(MvcResult result) throws Exception {
		return objectMapper.readTree(result.getResponse().getContentAsString());
	}
}
