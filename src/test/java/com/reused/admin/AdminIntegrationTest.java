package com.reused.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

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

import com.reused.TestcontainersConfiguration;
import com.reused.auth.client.OAuthProviderClient;
import com.reused.auth.mail.AuthMailSender;
import com.reused.auth.token.JwtTokenProvider;
import com.reused.image.storage.ImageStorage;
import com.reused.user.entity.UserRole;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AdminIntegrationTest {

	private static final String ADMIN_LISTINGS = "/api/v1/admin/listings";
	private static final String ADMIN_TRADES = "/api/v1/admin/trades";
	private static final Instant BASE_TIME = Instant.parse("2026-09-25T01:00:00Z");

	@Autowired private MockMvc mockMvc;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private ObjectMapper objectMapper;
	@Autowired private JwtTokenProvider tokenProvider;
	@MockitoBean private OAuthProviderClient kakaoClient;
	@MockitoBean private AuthMailSender mailSender;
	@MockitoBean private ImageStorage imageStorage;

	@BeforeEach
	void resetState() {
		jdbc.execute("TRUNCATE users RESTART IDENTITY CASCADE");
		when(imageStorage.presignRead(anyString(), any(Duration.class)))
				.thenAnswer(invocation -> "https://images.example.test/" + invocation.getArgument(0));
	}

	@Test
	@DisplayName("관리자 API는 토큰 주장뿐 아니라 현재 DB 역할도 다시 확인한다")
	void adminAuthorizationRechecksDatabaseRole() throws Exception {
		Long adminId = insertUser("관리자", UserRole.ADMIN, "ACTIVE");
		Long userId = insertUser("일반회원", UserRole.USER, "ACTIVE");

		mockMvc.perform(get(ADMIN_LISTINGS))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(get(ADMIN_LISTINGS)
					.header("Authorization", bearer(userId, UserRole.USER)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("FORBIDDEN"));
		mockMvc.perform(get(ADMIN_LISTINGS)
					.header("Authorization", bearer(userId, UserRole.ADMIN)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("FORBIDDEN"));

		String issuedAsAdmin = bearer(adminId, UserRole.ADMIN);
		jdbc.update("UPDATE users SET role = 'USER' WHERE user_id = ?", adminId);
		mockMvc.perform(get(ADMIN_LISTINGS).header("Authorization", issuedAsAdmin))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("FORBIDDEN"));
	}

	@Test
	@DisplayName("관리자 게시글 목록은 삭제 건을 포함하고 필터·신고 수·안정 커서를 제공한다")
	void adminListingListIncludesDeletedRowsAndSupportsFiltersAndCursor() throws Exception {
		Long adminId = insertUser("관리자", UserRole.ADMIN, "ACTIVE");
		Long firstSeller = insertUser("첫판매자", UserRole.USER, "ACTIVE");
		Long secondSeller = insertUser("둘판매자", UserRole.USER, "ACTIVE");
		Long reporter = insertUser("신고자", UserRole.USER, "ACTIVE");
		Long oldest = insertListing(firstSeller, "일반 상품", 1000, "ON_SALE", BASE_TIME);
		Long hidden = insertListing(firstSeller, "아이패드 숨김", 2000, "HIDDEN", BASE_TIME);
		Long deleted = insertListing(secondSeller, "아이패드 삭제", 3000, "COMPLETED", BASE_TIME);
		jdbc.update("UPDATE listings SET deleted_at = now(), deleted_by = ? WHERE listing_id = ?",
				adminId, deleted);
		insertReport(reporter, hidden, "FRAUD");
		insertReport(reporter, hidden, "SPAM");
		String authorization = bearer(adminId, UserRole.ADMIN);

		MvcResult firstPage = mockMvc.perform(get(ADMIN_LISTINGS).param("size", "2")
					.header("Authorization", authorization))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(2))
				.andExpect(jsonPath("$.items[0].listingId").value(deleted))
				.andExpect(jsonPath("$.items[0].isDeleted").value(true))
				.andExpect(jsonPath("$.items[1].listingId").value(hidden))
				.andExpect(jsonPath("$.items[1].reportCount").value(2))
				.andExpect(jsonPath("$.hasNext").value(true))
				.andReturn();
		String cursor = response(firstPage).get("nextCursor").asString();
		mockMvc.perform(get(ADMIN_LISTINGS).param("size", "2").param("cursor", cursor)
					.header("Authorization", authorization))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].listingId").value(oldest))
				.andExpect(jsonPath("$.hasNext").value(false));

		mockMvc.perform(get(ADMIN_LISTINGS)
					.param("status", "HIDDEN")
					.param("keyword", "아이패드")
					.param("sellerId", firstSeller.toString())
					.header("Authorization", authorization))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].listingId").value(hidden))
				.andExpect(jsonPath("$.items[0].seller.userId").value(firstSeller));
	}

	@Test
	@DisplayName("숨김과 복구는 기존 거래를 변경하지 않고 직전 게시글 상태와 사유를 감사 로그에 남긴다")
	void hideAndRestorePreserveExistingTradeAndAuditPreviousState() throws Exception {
		Long adminId = insertUser("관리자", UserRole.ADMIN, "ACTIVE");
		Long sellerId = insertUser("판매자", UserRole.USER, "ACTIVE");
		Long buyerId = insertUser("구매자", UserRole.USER, "ACTIVE");
		Long listingId = insertListing(sellerId, "예약 상품", 5000, "RESERVED", BASE_TIME);
		Long tradeId = insertTrade(listingId, sellerId, buyerId, "ACCEPTED", BASE_TIME);
		String authorization = bearer(adminId, UserRole.ADMIN);

		mockMvc.perform(json(patch(ADMIN_LISTINGS + "/{listingId}/status", listingId),
					Map.of("status", "HIDDEN", "reason", "  허위 매물 확인  "))
					.header("Authorization", authorization))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.listingId").value(listingId))
				.andExpect(jsonPath("$.status").value("HIDDEN"));
		assertThat(listingStatus(listingId)).isEqualTo("HIDDEN");
		assertThat(tradeStatus(tradeId)).isEqualTo("ACCEPTED");
		Map<String, Object> hideAudit = jdbc.queryForMap("""
				SELECT action, detail ->> 'reason' AS reason,
				       detail ->> 'beforeStatus' AS before_status,
				       detail ->> 'afterStatus' AS after_status
				FROM audit_logs WHERE target_type = 'LISTING' AND target_id = ?
				ORDER BY audit_log_id DESC LIMIT 1
				""", listingId);
		assertThat(hideAudit.get("action")).isEqualTo("LISTING_HIDE");
		assertThat(hideAudit.get("reason")).isEqualTo("허위 매물 확인");
		assertThat(hideAudit.get("before_status")).isEqualTo("RESERVED");
		assertThat(hideAudit.get("after_status")).isEqualTo("HIDDEN");

		mockMvc.perform(json(patch(ADMIN_LISTINGS + "/{listingId}/status", listingId),
					Map.of("status", "RESTORE", "reason", "오탐 확인"))
					.header("Authorization", authorization))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("RESERVED"));
		assertThat(listingStatus(listingId)).isEqualTo("RESERVED");
		assertThat(tradeStatus(tradeId)).isEqualTo("ACCEPTED");
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM audit_logs WHERE target_type = 'LISTING' AND target_id = ?",
				Long.class, listingId)).isEqualTo(2);

		mockMvc.perform(json(patch(ADMIN_LISTINGS + "/{listingId}/status", listingId),
					Map.of("status", "RESTORE", "reason", "중복 복구"))
					.header("Authorization", authorization))
				.andExpect(status().isConflict());
	}

	@Test
	@DisplayName("숨김 중 거래가 완료되면 노출은 숨김으로 유지하고 복구 시 완료 상태를 반영한다")
	void restoreDerivesCompletedStateFromTradeFinishedWhileHidden() throws Exception {
		Long adminId = insertUser("완료관리자", UserRole.ADMIN, "ACTIVE");
		Long sellerId = insertUser("완료판매자", UserRole.USER, "ACTIVE");
		Long buyerId = insertUser("완료구매자", UserRole.USER, "ACTIVE");
		Long listingId = insertListing(sellerId, "숨김 중 완료 상품", 5000, "RESERVED", BASE_TIME);
		Long tradeId = insertTrade(listingId, sellerId, buyerId, "ACCEPTED", BASE_TIME);
		String adminAuthorization = bearer(adminId, UserRole.ADMIN);

		hide(listingId, adminAuthorization, "분쟁 확인");
		mockMvc.perform(post("/api/v1/trades/{tradeId}/complete", tradeId)
					.header("Authorization", bearer(buyerId, UserRole.USER)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("COMPLETED"));
		assertThat(listingStatus(listingId)).isEqualTo("HIDDEN");

		restore(listingId, adminAuthorization);
		assertThat(listingStatus(listingId)).isEqualTo("COMPLETED");
		assertThat(tradeStatus(tradeId)).isEqualTo("COMPLETED");
	}

	@Test
	@DisplayName("숨김 중 승인 거래가 취소되면 노출은 숨김으로 유지하고 복구 시 판매 중이 된다")
	void restoreDerivesOnSaleStateFromTradeCanceledWhileHidden() throws Exception {
		Long adminId = insertUser("취소관리자", UserRole.ADMIN, "ACTIVE");
		Long sellerId = insertUser("취소판매자", UserRole.USER, "ACTIVE");
		Long buyerId = insertUser("취소구매자", UserRole.USER, "ACTIVE");
		Long listingId = insertListing(sellerId, "숨김 중 취소 상품", 5000, "RESERVED", BASE_TIME);
		Long tradeId = insertTrade(listingId, sellerId, buyerId, "ACCEPTED", BASE_TIME);
		String adminAuthorization = bearer(adminId, UserRole.ADMIN);

		hide(listingId, adminAuthorization, "분쟁 확인");
		mockMvc.perform(json(post("/api/v1/trades/{tradeId}/cancel", tradeId),
					Map.of("reason", "거래 취소"))
					.header("Authorization", bearer(buyerId, UserRole.USER)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("CANCELED"));
		assertThat(listingStatus(listingId)).isEqualTo("HIDDEN");

		restore(listingId, adminAuthorization);
		assertThat(listingStatus(listingId)).isEqualTo("ON_SALE");
		assertThat(tradeStatus(tradeId)).isEqualTo("CANCELED");
	}

	@Test
	@DisplayName("진행 중 거래 게시글은 관리자도 삭제할 수 없고 성공한 삭제만 감사한다")
	void adminDeleteRejectsActiveTradesAndAuditsSuccessfulSoftDelete() throws Exception {
		Long adminId = insertUser("관리자", UserRole.ADMIN, "ACTIVE");
		Long sellerId = insertUser("판매자", UserRole.USER, "ACTIVE");
		Long buyerId = insertUser("구매자", UserRole.USER, "ACTIVE");
		String authorization = bearer(adminId, UserRole.ADMIN);

		for (String tradeStatus : List.of("REQUESTED", "ACCEPTED")) {
			String listingStatus = "ACCEPTED".equals(tradeStatus) ? "RESERVED" : "ON_SALE";
			Long listingId = insertListing(sellerId, tradeStatus + " 상품", 1000, listingStatus, BASE_TIME);
			insertTrade(listingId, sellerId, buyerId, tradeStatus, BASE_TIME);
			mockMvc.perform(json(delete(ADMIN_LISTINGS + "/{listingId}", listingId),
						Map.of("reason", "운영 삭제"))
						.header("Authorization", authorization))
					.andExpect(status().isConflict())
					.andExpect(jsonPath("$.code").value("CONFLICT"));
			assertThat(jdbc.queryForObject(
					"SELECT deleted_at FROM listings WHERE listing_id = ?", Timestamp.class, listingId)).isNull();
		}
		assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs", Long.class)).isZero();

		Long completedListing = insertListing(sellerId, "완료 상품", 3000, "COMPLETED", BASE_TIME);
		insertTrade(completedListing, sellerId, buyerId, "COMPLETED", BASE_TIME);
		mockMvc.perform(json(delete(ADMIN_LISTINGS + "/{listingId}", completedListing),
					Map.of("reason", "금지 품목 판매"))
					.header("Authorization", authorization))
				.andExpect(status().isNoContent());
		Map<String, Object> deleted = jdbc.queryForMap(
				"SELECT deleted_at, deleted_by FROM listings WHERE listing_id = ?", completedListing);
		assertThat(deleted.get("deleted_at")).isNotNull();
		assertThat(((Number) deleted.get("deleted_by")).longValue()).isEqualTo(adminId);
		Map<String, Object> audit = jdbc.queryForMap("""
				SELECT action, actor_id, detail ->> 'reason' AS reason
				FROM audit_logs WHERE target_type = 'LISTING' AND target_id = ?
				""", completedListing);
		assertThat(audit.get("action")).isEqualTo("LISTING_DELETE");
		assertThat(((Number) audit.get("actor_id")).longValue()).isEqualTo(adminId);
		assertThat(audit.get("reason")).isEqualTo("금지 품목 판매");
	}

	@Test
	@DisplayName("관리자 거래 목록은 양측 사용자 필터와 동률 커서 및 게시글 썸네일을 제공한다")
	void adminTradeListSupportsPartyFilterStableCursorAndThumbnail() throws Exception {
		Long adminId = insertUser("관리자", UserRole.ADMIN, "ACTIVE");
		Long memberId = insertUser("조회회원", UserRole.USER, "ACTIVE");
		Long sellerId = insertUser("상대판매자", UserRole.USER, "ACTIVE");
		Long buyerId = insertUser("상대구매자", UserRole.USER, "ACTIVE");
		Long firstListing = insertListing(sellerId, "첫 거래 상품", 1000, "COMPLETED", BASE_TIME);
		Long secondListing = insertListing(memberId, "둘 거래 상품", 2000, "COMPLETED", BASE_TIME);
		Long unrelatedListing = insertListing(sellerId, "무관 상품", 3000, "RESERVED", BASE_TIME);
		Long firstTrade = insertTrade(firstListing, sellerId, memberId, "COMPLETED", BASE_TIME);
		Long secondTrade = insertTrade(secondListing, memberId, buyerId, "COMPLETED", BASE_TIME);
		insertTrade(unrelatedListing, sellerId, buyerId, "ACCEPTED", BASE_TIME.plusSeconds(1));
		jdbc.update("""
				INSERT INTO listing_images
				    (listing_id, uploader_id, object_key, content_type, file_size, status, display_order)
				VALUES (?, ?, 'admin/second.jpg', 'image/jpeg', 1024, 'VERIFIED', 0)
				""", secondListing, memberId);
		String authorization = bearer(adminId, UserRole.ADMIN);

		MvcResult firstPage = mockMvc.perform(get(ADMIN_TRADES)
					.param("status", "COMPLETED")
					.param("userId", memberId.toString())
					.param("size", "1")
					.header("Authorization", authorization))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].tradeId").value(secondTrade))
				.andExpect(jsonPath("$.items[0].seller.userId").value(memberId))
				.andExpect(jsonPath("$.items[0].buyer.userId").value(buyerId))
				.andExpect(jsonPath("$.items[0].listing.thumbnailUrl")
						.value("https://images.example.test/admin/second.jpg"))
				.andExpect(jsonPath("$.hasNext").value(true))
				.andReturn();
		String cursor = response(firstPage).get("nextCursor").asString();
		mockMvc.perform(get(ADMIN_TRADES)
					.param("status", "COMPLETED")
					.param("userId", memberId.toString())
					.param("size", "1")
					.param("cursor", cursor)
					.header("Authorization", authorization))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].tradeId").value(firstTrade))
				.andExpect(jsonPath("$.items[0].seller.userId").value(sellerId))
				.andExpect(jsonPath("$.items[0].buyer.userId").value(memberId))
				.andExpect(jsonPath("$.hasNext").value(false));
	}

	@Test
	@DisplayName("관리자 검색 조건과 상태 변경 입력을 검증한다")
	void adminEndpointsRejectInvalidInputs() throws Exception {
		Long adminId = insertUser("관리자", UserRole.ADMIN, "ACTIVE");
		Long sellerId = insertUser("판매자", UserRole.USER, "ACTIVE");
		Long listingId = insertListing(sellerId, "검증 상품", 1000, "ON_SALE", BASE_TIME);
		String authorization = bearer(adminId, UserRole.ADMIN);

		mockMvc.perform(get(ADMIN_LISTINGS).param("status", "UNKNOWN")
					.header("Authorization", authorization))
				.andExpect(status().isBadRequest());
		mockMvc.perform(get(ADMIN_LISTINGS).param("size", "101")
					.header("Authorization", authorization))
				.andExpect(status().isBadRequest());
		mockMvc.perform(get(ADMIN_TRADES).param("userId", "0")
					.header("Authorization", authorization))
				.andExpect(status().isBadRequest());
		mockMvc.perform(get(ADMIN_TRADES).param("cursor", "not-a-cursor")
					.header("Authorization", authorization))
				.andExpect(status().isBadRequest());
		mockMvc.perform(json(patch(ADMIN_LISTINGS + "/{listingId}/status", listingId),
					Map.of("status", "HIDDEN", "reason", "   "))
					.header("Authorization", authorization))
				.andExpect(status().isBadRequest());
	}

	private Long insertUser(String nickname, UserRole role, String status) {
		return jdbc.queryForObject("""
				INSERT INTO users (nickname, role, status, terms_agreed_at)
				VALUES (?, ?, ?, now()) RETURNING user_id
				""", Long.class, nickname, role.name(), status);
	}

	private Long insertListing(Long sellerId, String title, int price, String status, Instant createdAt) {
		Long categoryId = jdbc.queryForObject(
				"SELECT category_id FROM categories WHERE name = '디지털기기'", Long.class);
		return jdbc.queryForObject("""
				INSERT INTO listings
				    (seller_id, category_id, title, description, price, item_condition, trade_method, status, created_at)
				VALUES (?, ?, ?, ?, ?, 'LIKE_NEW', 'BOTH', ?, ?) RETURNING listing_id
				""", Long.class, sellerId, categoryId, title, title + " 설명", price, status,
				Timestamp.from(createdAt));
	}

	private Long insertTrade(Long listingId, Long sellerId, Long buyerId, String status, Instant requestedAt) {
		Timestamp acceptedAt = List.of("ACCEPTED", "COMPLETED").contains(status)
				? Timestamp.from(requestedAt.plusSeconds(1)) : null;
		Timestamp completedAt = "COMPLETED".equals(status)
				? Timestamp.from(requestedAt.plusSeconds(2)) : null;
		return jdbc.queryForObject("""
				INSERT INTO trades
				    (listing_id, seller_id, buyer_id, status, requested_at, accepted_at, completed_at)
				VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING trade_id
				""", Long.class, listingId, sellerId, buyerId, status, Timestamp.from(requestedAt),
				acceptedAt, completedAt);
	}

	private void insertReport(Long reporterId, Long listingId, String reasonCode) {
		jdbc.update("""
				INSERT INTO reports (reporter_id, target_type, target_id, reason_code)
				VALUES (?, 'LISTING', ?, ?)
				""", reporterId, listingId, reasonCode);
	}

	private String listingStatus(Long listingId) {
		return jdbc.queryForObject("SELECT status FROM listings WHERE listing_id = ?", String.class, listingId);
	}

	private String tradeStatus(Long tradeId) {
		return jdbc.queryForObject("SELECT status FROM trades WHERE trade_id = ?", String.class, tradeId);
	}

	private void hide(Long listingId, String authorization, String reason) throws Exception {
		mockMvc.perform(json(patch(ADMIN_LISTINGS + "/{listingId}/status", listingId),
					Map.of("status", "HIDDEN", "reason", reason))
					.header("Authorization", authorization))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("HIDDEN"));
	}

	private void restore(Long listingId, String authorization) throws Exception {
		mockMvc.perform(json(patch(ADMIN_LISTINGS + "/{listingId}/status", listingId),
					Map.of("status", "RESTORE", "reason", "분쟁 확인 완료"))
					.header("Authorization", authorization))
				.andExpect(status().isOk());
	}

	private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder, Map<String, ?> body) {
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
