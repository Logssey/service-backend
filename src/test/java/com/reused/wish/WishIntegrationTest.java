package com.reused.wish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.ObjectMapper;

import com.reused.TestcontainersConfiguration;
import com.reused.auth.client.OAuthProviderClient;
import com.reused.auth.mail.AuthMailSender;
import com.reused.auth.token.JwtTokenProvider;
import com.reused.image.storage.ImageStorage;
import com.reused.user.entity.UserRole;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class WishIntegrationTest {

	private static final String WISH = "/api/v1/listings/{listingId}/wish";
	private static final String WISHES = "/api/v1/wishes";
	private static final Instant BASE_TIME = Instant.parse("2026-09-23T01:00:00Z");

	@Autowired private MockMvc mockMvc;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private ObjectMapper objectMapper;
	@Autowired private JwtTokenProvider tokens;
	@MockitoBean private OAuthProviderClient kakaoOAuthClient;
	@MockitoBean private AuthMailSender mailSender;
	@MockitoBean private ImageStorage imageStorage;

	@BeforeEach
	void resetState() {
		jdbc.execute("TRUNCATE users RESTART IDENTITY CASCADE");
		when(imageStorage.presignRead(anyString(), any(Duration.class)))
				.thenAnswer(invocation -> "https://images.example.test/" + invocation.getArgument(0));
	}

	@Test
	@DisplayName("관심 등록과 해제는 멱등하며 본인 관계와 게시글 관심 수만 변경한다")
	void wishesAreIdempotentAndScopedToCurrentUser() throws Exception {
		long seller = user("판매자");
		long first = user("첫회원");
		long second = user("둘회원");
		long listing = listing(seller, "관심 상품", "ON_SALE", BASE_TIME);
		for (int i = 0; i < 2; i++) {
			mockMvc.perform(post(WISH, listing).header("Authorization", bearer(first)))
					.andExpect(status().isOk()).andExpect(jsonPath("$.wished").value(true))
					.andExpect(jsonPath("$.wishCount").value(1));
		}
		mockMvc.perform(post(WISH, listing).header("Authorization", bearer(second)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.wishCount").value(2));
		mockMvc.perform(get("/api/v1/listings/{id}", listing).header("Authorization", bearer(first)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.isWished").value(true))
				.andExpect(jsonPath("$.wishCount").value(2));
		for (int i = 0; i < 2; i++) {
			mockMvc.perform(delete(WISH, listing).header("Authorization", bearer(first)))
					.andExpect(status().isOk()).andExpect(jsonPath("$.wished").value(false))
					.andExpect(jsonPath("$.wishCount").value(1));
		}
		mockMvc.perform(get(WISHES).header("Authorization", bearer(first)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
		mockMvc.perform(get(WISHES).header("Authorization", bearer(second)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items[0].listingId").value(listing));
		mockMvc.perform(get("/api/v1/listings/{id}", listing).header("Authorization", bearer(first)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.isWished").value(false));
		assertCount(listing, 1);
	}

	@Test
	@DisplayName("여러 회원의 중복 등록과 해제 동시 요청은 관계와 관심 수를 일치시킨다")
	void concurrentRequestsKeepRelationAndCounterAtomic() throws Exception {
		long listing = listing(user("판매자"), "동시 관심", "ON_SALE", BASE_TIME);
		String first = bearer(user("첫회원"));
		String second = bearer(user("둘회원"));
		List<String> identities = List.of(first, first, second, second);
		assertConcurrent(listing, identities, true);
		assertCount(listing, 2);
		assertConcurrent(listing, identities, false);
		assertCount(listing, 0);
	}

	@Test
	@DisplayName("관심 목록은 관심 등록순으로 동률 커서를 이어가고 서명 썸네일을 제공한다")
	void pageUsesWishOrderAndStableCursorWithSignedThumbnail() throws Exception {
		long seller = user("판매자");
		long member = user("회원");
		long first = listing(seller, "새 게시글 먼저 관심", "ON_SALE", BASE_TIME.plusSeconds(20));
		long second = listing(seller, "예약 상품", "RESERVED", BASE_TIME.plusSeconds(10));
		long third = listing(seller, "오래된 게시글 나중 관심", "COMPLETED", BASE_TIME);
		wish(member, first, BASE_TIME);
		wish(member, second, BASE_TIME.plusSeconds(1));
		wish(member, third, BASE_TIME.plusSeconds(1));
		jdbc.update("""
				INSERT INTO listing_images
				(listing_id, uploader_id, object_key, content_type, file_size, status, display_order)
				VALUES (?, ?, 'wishes/thumb.jpg', 'image/jpeg', 1024, 'VERIFIED', 0)
				""", third, seller);
		MvcResult result = mockMvc.perform(get(WISHES).param("size", "2")
				.header("Authorization", bearer(member)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2))
				.andExpect(jsonPath("$.items[0].listingId").value(third))
				.andExpect(jsonPath("$.items[0].status").value("COMPLETED"))
				.andExpect(jsonPath("$.items[0].thumbnailUrl").value("https://images.example.test/wishes/thumb.jpg"))
				.andExpect(jsonPath("$.items[0].seller.userId").value(seller))
				.andExpect(jsonPath("$.items[0].wishCount").value(1))
				.andExpect(jsonPath("$.items[1].listingId").value(second))
				.andExpect(jsonPath("$.hasNext").value(true)).andReturn();
		String cursor = objectMapper.readTree(result.getResponse().getContentAsString()).get("nextCursor").asString();
		mockMvc.perform(get(WISHES).param("size", "2").param("cursor", cursor)
				.header("Authorization", bearer(member)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].listingId").value(first))
				.andExpect(jsonPath("$.hasNext").value(false)).andExpect(jsonPath("$.nextCursor").doesNotExist());
		mockMvc.perform(get(WISHES).param("cursor", cursor).header("Authorization", bearer(seller)))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
	}

	@Test
	@DisplayName("숨김·삭제·차단 판매자 게시글은 목록에서 제외하고 기존 관심 해제는 허용한다")
	void hiddenDeletedAndBlockedListingsDoNotLeakIntoWishList() throws Exception {
		long seller = user("판매자");
		long blockedSeller = user("차단판매자");
		long member = user("회원");
		long visible = listing(seller, "공개 상품", "ON_SALE", BASE_TIME);
		long hidden = listing(seller, "숨김 상품", "HIDDEN", BASE_TIME);
		long deleted = listing(seller, "삭제 상품", "ON_SALE", BASE_TIME);
		long blocked = listing(blockedSeller, "차단 상품", "ON_SALE", BASE_TIME);
		for (long id : List.of(visible, hidden, deleted, blocked)) {
			wish(member, id, BASE_TIME);
		}
		jdbc.update("UPDATE listings SET deleted_at = now(), deleted_by = ? WHERE listing_id = ?", seller, deleted);
		jdbc.update("INSERT INTO blocks (blocker_id, blocked_id) VALUES (?, ?)", member, blockedSeller);
		mockMvc.perform(get(WISHES).header("Authorization", bearer(member)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].listingId").value(visible));
		for (long id : List.of(hidden, deleted)) {
			mockMvc.perform(post(WISH, id).header("Authorization", bearer(member)))
					.andExpect(status().isNotFound());
			mockMvc.perform(delete(WISH, id).header("Authorization", bearer(member)))
					.andExpect(status().isOk()).andExpect(jsonPath("$.wishCount").value(0));
			assertCount(id, 0);
		}
	}

	@Test
	@DisplayName("관심 API는 비로그인·관리자·탈퇴와 변경된 토큰 권한을 검증한다")
	void authenticationAndCurrentRoleAreEnforced() throws Exception {
		long listing = listing(user("판매자"), "인증 상품", "ON_SALE", BASE_TIME);
		long member = user("회원");
		String authorization = bearer(member);
		mockMvc.perform(get(WISHES)).andExpect(status().isUnauthorized());
		mockMvc.perform(post(WISH, listing)).andExpect(status().isUnauthorized());
		mockMvc.perform(delete(WISH, listing)).andExpect(status().isUnauthorized());
		jdbc.update("UPDATE users SET role = 'ADMIN' WHERE user_id = ?", member);
		mockMvc.perform(post(WISH, listing).header("Authorization", authorization))
				.andExpect(status().isForbidden());
		String adminToken = "Bearer " + tokens.issueAccessToken(member, UserRole.ADMIN);
		mockMvc.perform(get(WISHES).header("Authorization", adminToken)).andExpect(status().isForbidden());
		jdbc.update("UPDATE users SET role = 'USER', status = 'WITHDRAWN' WHERE user_id = ?", member);
		mockMvc.perform(get(WISHES).header("Authorization", authorization)).andExpect(status().isUnauthorized());
		assertCount(listing, 0);
	}

	@Test
	@DisplayName("정지 회원의 개인 관심 목록 관리는 허용한다")
	void suspendedMemberCanManagePersonalWishes() throws Exception {
		long listing = listing(user("판매자"), "관심 상품", "ON_SALE", BASE_TIME);
		long member = user("정지회원");
		jdbc.update("UPDATE users SET status = 'SUSPENDED' WHERE user_id = ?", member);
		mockMvc.perform(post(WISH, listing).header("Authorization", bearer(member))).andExpect(status().isOk());
		mockMvc.perform(get(WISHES).header("Authorization", bearer(member)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1));
		mockMvc.perform(delete(WISH, listing).header("Authorization", bearer(member))).andExpect(status().isOk());
	}

	@Test
	@DisplayName("잘못된 ID·페이지 크기·커서는 400이고 없는 게시글은 404이다")
	void invalidInputsAreRejected() throws Exception {
		long member = user("회원");
		String authorization = bearer(member);
		for (String id : List.of("0", "-1", "not-a-number")) {
			mockMvc.perform(post(WISH, id).header("Authorization", authorization)).andExpect(status().isBadRequest());
			mockMvc.perform(delete(WISH, id).header("Authorization", authorization)).andExpect(status().isBadRequest());
		}
		mockMvc.perform(post(WISH, 999999).header("Authorization", authorization)).andExpect(status().isNotFound());
		mockMvc.perform(delete(WISH, 999999).header("Authorization", authorization)).andExpect(status().isNotFound());
		for (String size : List.of("0", "101", "invalid")) {
			mockMvc.perform(get(WISHES).param("size", size).header("Authorization", authorization))
					.andExpect(status().isBadRequest());
		}
		String outOfRange = Base64.getUrlEncoder().encodeToString(
				("wishes-v1|" + member + "|+1000000000-12-31T23:59:59Z|1").getBytes(StandardCharsets.UTF_8));
		for (String cursor : List.of("", "invalid", "x".repeat(513), outOfRange)) {
			mockMvc.perform(get(WISHES).param("cursor", cursor).header("Authorization", authorization))
					.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
		}
	}

	private void assertConcurrent(long listing, List<String> identities, boolean add) throws Exception {
		CountDownLatch ready = new CountDownLatch(identities.size());
		CountDownLatch start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(identities.size())) {
			List<Future<Integer>> results = new ArrayList<>();
			for (String authorization : identities) {
				results.add(executor.submit(() -> {
					ready.countDown();
					if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Concurrent start timed out");
					return mockMvc.perform((add ? post(WISH, listing) : delete(WISH, listing))
							.header("Authorization", authorization)).andReturn().getResponse().getStatus();
				}));
			}
			assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			for (Future<Integer> result : results) {
				assertThat(result.get(20, TimeUnit.SECONDS)).isEqualTo(200);
			}
		}
	}

	private void assertCount(long listing, int count) {
		assertThat(jdbc.queryForObject("SELECT wish_count FROM listings WHERE listing_id = ?", Integer.class, listing))
				.isEqualTo(count);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM wishes WHERE listing_id = ?", Integer.class, listing))
				.isEqualTo(count);
	}

	private long user(String nickname) {
		return jdbc.queryForObject("INSERT INTO users (nickname, terms_agreed_at) VALUES (?, now()) RETURNING user_id",
				Long.class, nickname);
	}

	private long listing(long seller, String title, String status, Instant createdAt) {
		Long category = jdbc.queryForObject("SELECT category_id FROM categories WHERE name = '디지털기기'", Long.class);
		return jdbc.queryForObject("""
				INSERT INTO listings (seller_id, category_id, title, description, price, item_condition,
				trade_method, status, created_at) VALUES (?, ?, ?, '설명', 10000, 'LIKE_NEW', 'BOTH', ?, ?)
				RETURNING listing_id
				""", Long.class, seller, category, title, status, Timestamp.from(createdAt));
	}

	private void wish(long user, long listing, Instant createdAt) {
		jdbc.update("INSERT INTO wishes (user_id, listing_id, created_at) VALUES (?, ?, ?)",
				user, listing, Timestamp.from(createdAt));
		jdbc.update("UPDATE listings SET wish_count = wish_count + 1 WHERE listing_id = ?", listing);
	}

	private String bearer(long user) {
		return "Bearer " + tokens.issueAccessToken(user, UserRole.USER);
	}
}
