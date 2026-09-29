package com.reused.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
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
class ReviewMeIntegrationTest {
    private static final Instant BASE = Instant.parse("2026-09-01T12:00:00Z");
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired JwtTokenProvider tokens;
    @MockitoBean OAuthProviderClient kakaoOAuthClient;
    @MockitoBean AuthMailSender mailSender;
    @MockitoBean ImageStorage imageStorage;

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE users RESTART IDENTITY CASCADE");
        when(imageStorage.presignRead(anyString(), any(Duration.class)))
                .thenAnswer(invocation -> "https://images.example.test/" + invocation.getArgument(0));
    }

    @Test
    void completedMarketplaceFlowAllowsOneReviewPerPartyAndNotifiesRecipient() throws Exception {
        Long seller = user("판매자");
        Long buyer = user("구매자");
        Long listing = listing(seller, "ON_SALE", BASE);
        Long trade = id(mvc.perform(json(post("/api/v1/trades"), Map.of("listingId", listing))
                        .header("Authorization", bearer(buyer)))
                .andExpect(status().isCreated()).andReturn(), "tradeId");
        mvc.perform(post("/api/v1/trades/{id}/accept", trade).header("Authorization", bearer(seller)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/trades/{id}/complete", trade).header("Authorization", bearer(buyer)))
                .andExpect(status().isOk());

        Long buyerReview = id(mvc.perform(json(post("/api/v1/reviews"),
                        Map.of("tradeId", trade, "rating", 5, "content", "  친절했습니다  "))
                        .header("Authorization", bearer(buyer)))
                .andExpect(status().isCreated()).andReturn(), "reviewId");
        Long sellerReview = id(mvc.perform(json(post("/api/v1/reviews"), Map.of("tradeId", trade, "rating", 4))
                        .header("Authorization", bearer(seller)))
                .andExpect(status().isCreated()).andReturn(), "reviewId");

        assertThat(jdbc.queryForObject("SELECT reviewee_id FROM reviews WHERE review_id = ?", Long.class, buyerReview))
                .isEqualTo(seller);
        assertThat(jdbc.queryForObject("SELECT reviewee_id FROM reviews WHERE review_id = ?", Long.class, sellerReview))
                .isEqualTo(buyer);
        assertThat(jdbc.queryForObject("SELECT content FROM reviews WHERE review_id = ?", String.class, buyerReview))
                .isEqualTo("친절했습니다");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE type = 'REVIEW_RECEIVED'", Long.class))
                .isEqualTo(2);
        mvc.perform(get("/api/v1/me/reviews").header("Authorization", bearer(seller)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].reviewId").value(buyerReview));
        mvc.perform(get("/api/v1/trades/{id}", trade).header("Authorization", bearer(buyer)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.reviewWritten").value(true));
        mvc.perform(get("/api/v1/users/{id}/profile", seller)).andExpect(status().isOk())
                .andExpect(jsonPath("$.completedTradeCount").value(1))
                .andExpect(jsonPath("$.averageRating").value(5.0));

        // Moderation must not give the original author another submission slot.
        jdbc.update("UPDATE reviews SET deleted_at = now() WHERE review_id = ?", buyerReview);
        mvc.perform(json(post("/api/v1/reviews"), Map.of("tradeId", trade, "rating", 5))
                        .header("Authorization", bearer(buyer)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void reviewRequiresCompletedTradeAndCurrentActiveUserParty() throws Exception {
        Long seller = user("판매자");
        Long buyer = user("구매자");
        Long outsider = user("제삼자");
        Long trade = trade(listing(seller, "COMPLETED", BASE), seller, buyer, "COMPLETED");
        Map<String, Object> request = Map.of("tradeId", trade, "rating", 4);
        mvc.perform(json(post("/api/v1/reviews"), request)).andExpect(status().isUnauthorized());
        mvc.perform(json(post("/api/v1/reviews"), request).header("Authorization", bearer(outsider)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(json(post("/api/v1/reviews"), Map.of("tradeId", 999999, "rating", 4))
                        .header("Authorization", bearer(buyer)))
                .andExpect(status().isNotFound());
        String staleToken = bearer(buyer);
        jdbc.update("UPDATE users SET status = 'SUSPENDED' WHERE user_id = ?", buyer);
        mvc.perform(json(post("/api/v1/reviews"), request).header("Authorization", staleToken))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("USER_SUSPENDED"));
        jdbc.update("UPDATE users SET status = 'ACTIVE', role = 'ADMIN' WHERE user_id = ?", buyer);
        mvc.perform(json(post("/api/v1/reviews"), request).header("Authorization", staleToken))
                .andExpect(status().isForbidden());
        jdbc.update("UPDATE users SET status = 'WITHDRAWN', role = 'USER' WHERE user_id = ?", buyer);
        mvc.perform(json(post("/api/v1/reviews"), request).header("Authorization", staleToken))
                .andExpect(status().isUnauthorized());
        jdbc.update("UPDATE users SET status = 'ACTIVE' WHERE user_id = ?", buyer);
        for (String state : List.of("REQUESTED", "ACCEPTED", "REJECTED", "CANCELED")) {
            Long incomplete = trade(listing(seller, "ON_SALE", BASE), seller, buyer, state);
            mvc.perform(json(post("/api/v1/reviews"), Map.of("tradeId", incomplete, "rating", 4))
                            .header("Authorization", bearer(buyer)))
                    .andExpect(status().isConflict());
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reviews", Long.class)).isZero();
    }

    @Test
    void validatesReviewAndPagingInput() throws Exception {
        Long seller = user("판매자");
        Long buyer = user("구매자");
        Long trade = trade(listing(seller, "COMPLETED", BASE), seller, buyer, "COMPLETED");
        for (Map<String, ?> request : List.<Map<String, ?>>of(Map.of("tradeId", trade, "rating", 0),
                Map.of("tradeId", trade, "rating", 6), Map.of("tradeId", trade), Map.of("rating", 5),
                Map.of("tradeId", -1, "rating", 5), Map.of("tradeId", trade, "rating", 5, "content", "가".repeat(501)))) {
            mvc.perform(json(post("/api/v1/reviews"), request).header("Authorization", bearer(buyer)))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }
        for (String path : List.of("/api/v1/me/reviews", "/api/v1/me/selling/listings",
                "/api/v1/users/" + seller + "/reviews", "/api/v1/users/" + seller + "/listings")) {
            mvc.perform(get(path).param("size", "101").header("Authorization", bearer(seller)))
                    .andExpect(status().isBadRequest());
            mvc.perform(get(path).param("cursor", "invalid").header("Authorization", bearer(seller)))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/v1/me/selling/listings").param("status", "INVALID")
                        .header("Authorization", bearer(seller))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/users/-1/profile")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/users/999999/reviews")).andExpect(status().isNotFound());
    }

    @Test
    void concurrentReviewSubmissionsCreateExactlyOneReviewAndNotification() throws Exception {
        Long seller = user("판매자");
        Long buyer = user("구매자");
        Long trade = trade(listing(seller, "COMPLETED", BASE), seller, buyer, "COMPLETED");
        String authorization = bearer(buyer);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var tasks = List.of(1, 2).stream().map(ignored -> executor.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("start timed out");
                return mvc.perform(json(post("/api/v1/reviews"), Map.of("tradeId", trade, "rating", 5))
                                .header("Authorization", authorization)).andReturn().getResponse().getStatus();
            })).toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(tasks.get(0).get(20, TimeUnit.SECONDS), tasks.get(1).get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(201, 409);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reviews WHERE trade_id = ?", Long.class, trade)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE type = 'REVIEW_RECEIVED'", Long.class)).isOne();
    }

    @Test
    void receivedReviewsHaveStableTimeCursorAndRetainAnonymousRatings() throws Exception {
        Long seller = user("판매자");
        Long firstBuyer = user("이전구매자");
        Long secondBuyer = user("현재구매자");
        Long first = review(seller, firstBuyer, 5, BASE.plusSeconds(60));
        Long second = review(seller, secondBuyer, 3, BASE.plusSeconds(60));
        Long older = review(seller, secondBuyer, 4, BASE);
        Long hidden = review(seller, secondBuyer, 1, BASE.plusSeconds(120));
        jdbc.update("UPDATE reviews SET deleted_at = now() WHERE review_id = ?", hidden);
        jdbc.update("UPDATE users SET status = 'WITHDRAWN', profile_image_url = 'private-legacy-photo' WHERE user_id = ?", firstBuyer);
        MvcResult page = mvc.perform(get("/api/v1/users/{id}/reviews", seller).param("size", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].reviewId").value(second))
                .andExpect(jsonPath("$.items[1].reviewId").value(first))
                .andExpect(jsonPath("$.items[1].reviewer.nickname").value("탈퇴회원"))
                .andExpect(jsonPath("$.items[1].reviewer.userId").doesNotExist())
                .andExpect(jsonPath("$.items[1].reviewer.profileImageUrl").doesNotExist())
                .andExpect(jsonPath("$.hasNext").value(true)).andReturn();
        String cursor = mapper.readTree(page.getResponse().getContentAsString()).get("nextCursor").asString();
        mvc.perform(get("/api/v1/users/{id}/reviews", seller).param("size", "2").param("cursor", cursor))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].reviewId").value(older))
                .andExpect(jsonPath("$.hasNext").value(false));
        mvc.perform(get("/api/v1/users/{id}/reviews", secondBuyer).param("cursor", cursor))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/me/reviews").header("Authorization", bearer(seller)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(3));
        mvc.perform(get("/api/v1/users/{id}/profile", seller))
                .andExpect(status().isOk()).andExpect(jsonPath("$.averageRating").value(4.0))
                .andExpect(jsonPath("$.reviewCount").value(3))
                .andExpect(jsonPath("$.completedTradeCount").value(4));
        mvc.perform(get("/api/v1/users/{id}/profile", firstBuyer)).andExpect(status().isNotFound());
    }

    @Test
    void sellerWarningCountsOnlyRecentResolvedReportsForSellerAndOwnedContent() throws Exception {
        Long seller = user("판매자");
        Long other = user("다른판매자");
        Long reporter = user("신고자");
        Long listing = listing(seller, "ON_SALE", BASE);
        report(reporter, "USER", seller, "RESOLVED", "1 day");
        report(reporter, "LISTING", listing, "RESOLVED", "2 days");
        report(reporter, "USER", seller, "RESOLVED", "91 days");
        report(reporter, "USER", other, "RESOLVED", "1 day");
        report(reporter, "USER", seller, "REJECTED", "1 day");
        mvc.perform(get("/api/v1/users/{id}/profile", seller)).andExpect(status().isOk())
                .andExpect(jsonPath("$.reportFlag").value(false))
                .andExpect(jsonPath("$.averageRating").doesNotExist())
                .andExpect(jsonPath("$.reviewCount").value(0));
        report(reporter, "USER", seller, "RESOLVED", "3 days");
        mvc.perform(get("/api/v1/users/{id}/profile", seller)).andExpect(status().isOk())
                .andExpect(jsonPath("$.reportFlag").value(true));
    }

    @Test
    void mineIncludesHiddenListingsAndPendingCountsWhilePublicShowsOnlyAvailableListings() throws Exception {
        Long seller = user("판매자");
        Long buyer = user("구매자");
        Long anotherBuyer = user("다른구매자");
        Long sale = listing(seller, "ON_SALE", BASE);
        Long reserved = listing(seller, "RESERVED", BASE);
        Long completed = listing(seller, "COMPLETED", BASE);
        Long hidden = listing(seller, "HIDDEN", BASE);
        Long deleted = listing(seller, "ON_SALE", BASE);
        jdbc.update("UPDATE listings SET deleted_at = now() WHERE listing_id = ?", deleted);
        listing(buyer, "ON_SALE", BASE.plusSeconds(60));
        trade(sale, seller, buyer, "REQUESTED");
        trade(sale, seller, anotherBuyer, "CANCELED");
        trade(reserved, seller, buyer, "ACCEPTED");
        jdbc.update("""
                INSERT INTO listing_images (listing_id, uploader_id, object_key, content_type, file_size, status, display_order)
                VALUES (?, ?, 'verified/seller/photo.jpg', 'image/jpeg', 1024, 'VERIFIED', 0)
                """, sale, seller);
        mvc.perform(get("/api/v1/me/selling/listings").header("Authorization", bearer(seller)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(4))
                .andExpect(jsonPath("$.items[0].listingId").value(hidden))
                .andExpect(jsonPath("$.items[1].listingId").value(completed))
                .andExpect(jsonPath("$.items[2].listingId").value(reserved))
                .andExpect(jsonPath("$.items[2].pendingTradeCount").value(0))
                .andExpect(jsonPath("$.items[3].pendingTradeCount").value(1))
                .andExpect(jsonPath("$.items[3].thumbnailUrl").value("https://images.example.test/verified/seller/photo.jpg"));
        mvc.perform(get("/api/v1/me/selling/listings").param("status", "HIDDEN")
                        .header("Authorization", bearer(seller)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].listingId").value(hidden));
        mvc.perform(get("/api/v1/users/{id}/listings", seller))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].listingId").value(sale))
                .andExpect(jsonPath("$.items[0].thumbnailUrl").value("https://images.example.test/verified/seller/photo.jpg"))
                .andExpect(jsonPath("$.items[0].seller.userId").value(seller));
        jdbc.update("INSERT INTO blocks (blocker_id, blocked_id) VALUES (?, ?)", buyer, seller);
        mvc.perform(get("/api/v1/users/{id}/listings", seller).header("Authorization", bearer(buyer)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void listingCursorsUseCreationTimeTieBreakerAndAreBoundToOwnerAndFilter() throws Exception {
        Long seller = user("판매자");
        Long other = user("다른판매자");
        Long first = listing(seller, "ON_SALE", BASE.plusSeconds(60));
        Long second = listing(seller, "ON_SALE", BASE.plusSeconds(60));
        Long older = listing(seller, "ON_SALE", BASE);
        MvcResult result = mvc.perform(get("/api/v1/me/selling/listings").param("size", "2")
                        .param("status", "ON_SALE").header("Authorization", bearer(seller)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].listingId").value(second))
                .andExpect(jsonPath("$.items[1].listingId").value(first)).andReturn();
        String cursor = mapper.readTree(result.getResponse().getContentAsString()).get("nextCursor").asString();
        mvc.perform(get("/api/v1/me/selling/listings").param("cursor", cursor).param("status", "ON_SALE")
                        .header("Authorization", bearer(seller)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].listingId").value(older));
        mvc.perform(get("/api/v1/me/selling/listings").param("cursor", cursor)
                        .header("Authorization", bearer(seller))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/me/selling/listings").param("cursor", cursor).param("status", "ON_SALE")
                        .header("Authorization", bearer(other))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/users/{id}/listings", seller).param("cursor", cursor))
                .andExpect(status().isBadRequest());
        MvcResult publicResult = mvc.perform(get("/api/v1/users/{id}/listings", seller).param("size", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].listingId").value(second)).andReturn();
        String publicCursor = mapper.readTree(publicResult.getResponse().getContentAsString()).get("nextCursor").asString();
        mvc.perform(get("/api/v1/users/{id}/listings", seller).param("cursor", publicCursor))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].listingId").value(older))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    void privateMeEndpointsRequireUserButAllowSuspendedUsersToReadExistingData() throws Exception {
        Long seller = user("판매자");
        String bearer = bearer(seller);
        jdbc.update("UPDATE users SET status = 'SUSPENDED' WHERE user_id = ?", seller);
        for (String path : List.of("/api/v1/me/reviews", "/api/v1/me/selling/listings")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            mvc.perform(get(path).header("Authorization", bearer)).andExpect(status().isOk());
        }
        jdbc.update("UPDATE users SET status = 'ACTIVE', role = 'ADMIN' WHERE user_id = ?", seller);
        for (String path : List.of("/api/v1/me/reviews", "/api/v1/me/selling/listings")) {
            mvc.perform(get(path).header("Authorization", bearer)).andExpect(status().isForbidden());
        }
    }

    @Test
    void reviewNotificationRespectsRecipientSettings() throws Exception {
        Long seller = user("판매자");
        Long buyer = user("구매자");
        Long trade = trade(listing(seller, "COMPLETED", BASE), seller, buyer, "COMPLETED");
        jdbc.update("INSERT INTO notification_settings (user_id, review_enabled) VALUES (?, false)", seller);
        mvc.perform(json(post("/api/v1/reviews"), Map.of("tradeId", trade, "rating", 5))
                        .header("Authorization", bearer(buyer))).andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reviews", Long.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications", Long.class)).isZero();
    }

    private Long user(String nickname) {
        return jdbc.queryForObject("INSERT INTO users (nickname, terms_agreed_at) VALUES (?, now()) RETURNING user_id",
                Long.class, nickname);
    }
    private Long listing(Long seller, String status, Instant createdAt) {
        Long category = jdbc.queryForObject("SELECT min(category_id) FROM categories", Long.class);
        return jdbc.queryForObject("""
                INSERT INTO listings (seller_id, category_id, title, description, price, item_condition, trade_method, status, created_at)
                VALUES (?, ?, '중고 상품', '거래할 상품 설명', 10000, 'LIKE_NEW', 'BOTH', ?, ?) RETURNING listing_id
                """, Long.class, seller, category, status, Timestamp.from(createdAt));
    }
    private Long trade(Long listing, Long seller, Long buyer, String status) {
        return jdbc.queryForObject("""
                INSERT INTO trades (listing_id, seller_id, buyer_id, status, completed_at)
                VALUES (?, ?, ?, ?, CASE WHEN ? = 'COMPLETED' THEN now() ELSE NULL END) RETURNING trade_id
                """, Long.class, listing, seller, buyer, status, status);
    }
    private Long review(Long seller, Long buyer, int rating, Instant createdAt) {
        Long trade = trade(listing(seller, "COMPLETED", BASE), seller, buyer, "COMPLETED");
        return jdbc.queryForObject("""
                INSERT INTO reviews (trade_id, reviewer_id, reviewee_id, rating, content, created_at)
                VALUES (?, ?, ?, ?, '거래 후기', ?) RETURNING review_id
                """, Long.class, trade, buyer, seller, rating, Timestamp.from(createdAt));
    }
    private void report(Long reporter, String type, Long target, String state, String age) {
        jdbc.update("""
                INSERT INTO reports (reporter_id, target_type, target_id, reason_code, status, handled_at)
                VALUES (?, ?, ?, 'FRAUD', ?, now() - cast(? as interval))
                """, reporter, type, target, state, age);
    }
    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, Map<String, ?> body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body));
    }
    private String bearer(Long id) { return "Bearer " + tokens.issueAccessToken(id, UserRole.USER); }
    private Long id(MvcResult response, String field) throws Exception {
        return mapper.readTree(response.getResponse().getContentAsString()).get(field).asLong();
    }
}
