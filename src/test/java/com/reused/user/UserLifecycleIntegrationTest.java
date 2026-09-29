package com.reused.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
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
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;

import com.reused.TestcontainersConfiguration;
import com.reused.auth.client.OAuthProviderClient;
import com.reused.auth.mail.AuthMailSender;
import com.reused.auth.token.InvalidTokenException;
import com.reused.auth.token.JwtTokenProvider;
import com.reused.auth.token.RefreshTokenStore;
import com.reused.image.storage.ImageStorage;
import com.reused.user.entity.UserRole;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class UserLifecycleIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired JwtTokenProvider tokens;
    @Autowired RefreshTokenStore refreshTokens;
    @Autowired StringRedisTemplate redis;
    @MockitoBean OAuthProviderClient kakaoOAuthClient;
    @MockitoBean AuthMailSender mailSender;
    @MockitoBean ImageStorage imageStorage;

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE users RESTART IDENTITY CASCADE");
        redis.execute((RedisCallback<Void>) connection -> { connection.serverCommands().flushDb(); return null; });
        when(imageStorage.presignRead(anyString(), any(Duration.class)))
                .thenAnswer(call -> "https://images.example.test/" + call.getArgument(0));
    }

    @Test
    void patchUsesPresenceAndReturnsCurrentProfileWhileRejectingInvalidAndConflictingFields() throws Exception {
        Long owner = user("원래이름");
        user("이미있는이름");
        jdbc.update("UPDATE users SET bio = '원래소개', profile_image_url = 'https://oauth.example.test/avatar.jpg' WHERE user_id = ?", owner);
        mvc.perform(json(patch("/api/v1/users/me"), Map.of("nickname", " 새이름 ")).header("Authorization", bearer(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.nickname").value("새이름"))
                .andExpect(jsonPath("$.bio").value("원래소개"))
                .andExpect(jsonPath("$.profileImageUrl").value("https://oauth.example.test/avatar.jpg"));
        mvc.perform(patch("/api/v1/users/me").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bio\":null,\"imageId\":null}").header("Authorization", bearer(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.bio").doesNotExist())
                .andExpect(jsonPath("$.profileImageUrl").doesNotExist());
        for (Map<String, ?> body : List.<Map<String, ?>>of(Map.of("nickname", " "), Map.of("nickname", " a "),
                Map.of("nickname", "가".repeat(21)), Map.of("bio", "가".repeat(201)), Map.of("imageId", -1),
                Map.of("nickname", "탈퇴회원#1"), Map.of("profileImageUrl", "https://untrusted.test/image.jpg"), Map.of("role", "ADMIN"))) {
            mvc.perform(json(patch("/api/v1/users/me"), body).header("Authorization", bearer(owner)))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(json(patch("/api/v1/users/me"), Map.of("nickname", "이미있는이름", "bio", "변경안됨"))
                        .header("Authorization", bearer(owner))).andExpect(status().isConflict());
        mvc.perform(get("/api/v1/users/me").header("Authorization", bearer(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.nickname").value("새이름"))
                .andExpect(jsonPath("$.bio").doesNotExist());
    }

    @Test
    void profileChangeChecksCurrentRoleAndSuspensionButSuspendedUserMayWithdraw() throws Exception {
        Long owner = user("회원");
        String access = bearer(owner);
        mvc.perform(json(patch("/api/v1/users/me"), Map.of("bio", "안녕"))).andExpect(status().isUnauthorized());
        jdbc.update("UPDATE users SET status = 'SUSPENDED' WHERE user_id = ?", owner);
        mvc.perform(json(patch("/api/v1/users/me"), Map.of("bio", "안녕")).header("Authorization", access))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("USER_SUSPENDED"));
        jdbc.update("UPDATE users SET role = 'ADMIN' WHERE user_id = ?", owner);
        mvc.perform(json(patch("/api/v1/users/me"), Map.of("bio", "안녕")).header("Authorization", access))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/users/me").header("Authorization", access)).andExpect(status().isForbidden());
        jdbc.update("UPDATE users SET role = 'USER' WHERE user_id = ?", owner);
        mvc.perform(delete("/api/v1/users/me").header("Authorization", access)).andExpect(status().isNoContent());
        mvc.perform(json(patch("/api/v1/users/me"), Map.of("bio", "안녕")).header("Authorization", access))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void concurrentProfileReplacementsKeepExactlyOneOwnedImageAttached() throws Exception {
        Long owner = user("회원");
        Long first = image(owner, "first");
        Long second = image(owner, "second");
        String access = bearer(owner);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var tasks = List.of(first, second).stream().map(id -> executor.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
                return mvc.perform(json(patch("/api/v1/users/me"), Map.of("imageId", id))
                        .header("Authorization", access)).andReturn().getResponse().getStatus();
            })).toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(tasks.get(0).get(20, TimeUnit.SECONDS)).isEqualTo(200);
            assertThat(tasks.get(1).get(20, TimeUnit.SECONDS)).isEqualTo(200);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM listing_images WHERE profile_user_id = ?", Long.class, owner)).isOne();
        String key = jdbc.queryForObject("SELECT object_key FROM listing_images WHERE profile_user_id = ?", String.class, owner);
        assertThat(jdbc.queryForObject("SELECT profile_image_url FROM users WHERE user_id = ?", String.class, owner)).isEqualTo(key);
    }

    @Test
    void withdrawalCancelsActiveTradesPreservesContentAndRevokesIdentityAndSessions() throws Exception {
        Long owner = user("탈퇴할회원");
        Long other = user("거래상대방");
        Long requestedListing = listing(owner, "ON_SALE");
        Long requested = trade(requestedListing, owner, other, "REQUESTED");
        Long reservedListing = listing(other, "RESERVED");
        Long accepted = trade(reservedListing, other, owner, "ACCEPTED");
        Long hiddenListing = listing(other, "HIDDEN");
        Long hiddenTrade = trade(hiddenListing, other, owner, "ACCEPTED");
        Long completedListing = listing(other, "COMPLETED");
        Long completed = trade(completedListing, other, owner, "COMPLETED");
        jdbc.update("INSERT INTO reviews (trade_id, reviewer_id, reviewee_id, rating, content) VALUES (?, ?, ?, 5, '기존 후기')",
                completed, owner, other);
        Long profile = image(owner, "avatar");
        jdbc.update("UPDATE listing_images SET profile_user_id = ? WHERE image_id = ?", owner, profile);
        jdbc.update("UPDATE users SET bio = '지워질 소개', profile_image_url = ? WHERE user_id = ?",
                "verified/profile-images/" + owner + "/avatar.jpg", owner);
        Long room = jdbc.queryForObject("INSERT INTO chat_rooms (listing_id, seller_id, buyer_id) VALUES (?, ?, ?) RETURNING chat_room_id",
                Long.class, completedListing, other, owner);
        jdbc.update("INSERT INTO messages (chat_room_id, sender_id, content) VALUES (?, ?, '기존 메시지')", room, owner);
        String firstRefresh = refreshTokens.issue(owner);
        String secondRefresh = refreshTokens.issue(owner);
        String access = bearer(owner);

        mvc.perform(delete("/api/v1/users/me").header("Authorization", access)).andExpect(status().isNoContent());
        var state = jdbc.queryForMap("SELECT nickname, status, bio, profile_image_url, withdrawn_at FROM users WHERE user_id = ?", owner);
        assertThat(state.get("nickname")).isEqualTo("탈퇴회원#" + owner);
        assertThat(state.get("status")).isEqualTo("WITHDRAWN");
        assertThat(state.get("bio")).isNull();
        assertThat(state.get("profile_image_url")).isNull();
        assertThat(state.get("withdrawn_at")).isNotNull();
        for (Long trade : List.of(requested, accepted, hiddenTrade)) {
            assertThat(jdbc.queryForObject("SELECT status FROM trades WHERE trade_id = ?", String.class, trade)).isEqualTo("CANCELED");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM trade_status_histories WHERE trade_id = ? AND after_status = 'CANCELED'",
                    Long.class, trade)).isOne();
        }
        assertThat(jdbc.queryForObject("SELECT status FROM trades WHERE trade_id = ?", String.class, completed)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT status FROM listings WHERE listing_id = ?", String.class, reservedListing)).isEqualTo("ON_SALE");
        assertThat(jdbc.queryForObject("SELECT status FROM listings WHERE listing_id = ?", String.class, hiddenListing)).isEqualTo("HIDDEN");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM listings WHERE seller_id = ?", Long.class, owner)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reviews WHERE reviewer_id = ?", Long.class, owner)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM messages WHERE sender_id = ?", Long.class, owner)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id = ? AND type = 'TRADE_CANCELED'", Long.class, other)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT profile_user_id FROM listing_images WHERE image_id = ?", Long.class, profile)).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_identities WHERE user_id = ?", Long.class, owner)).isZero();
        assertThatThrownBy(() -> refreshTokens.rotate(firstRefresh)).isInstanceOf(InvalidTokenException.class);
        assertThatThrownBy(() -> refreshTokens.rotate(secondRefresh)).isInstanceOf(InvalidTokenException.class);
        mvc.perform(get("/api/v1/users/me").header("Authorization", access)).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/v1/users/me").header("Authorization", access)).andExpect(status().isUnauthorized());
    }

    @Test
    void withdrawalAndNewTradeRequestCannotLeaveActiveTradeForWithdrawnSeller() throws Exception {
        Long seller = user("탈퇴할판매자");
        Long buyer = user("구매자");
        Long listing = listing(seller, "ON_SALE");
        String sellerToken = bearer(seller);
        String buyerToken = bearer(buyer);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var withdrawal = executor.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return mvc.perform(delete("/api/v1/users/me").header("Authorization", sellerToken)).andReturn().getResponse().getStatus();
            });
            var request = executor.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return mvc.perform(json(post("/api/v1/trades"), Map.of("listingId", listing))
                        .header("Authorization", buyerToken)).andReturn().getResponse().getStatus();
            });
            start.countDown();
            assertThat(withdrawal.get(20, TimeUnit.SECONDS)).isEqualTo(204);
            assertThat(request.get(20, TimeUnit.SECONDS)).isIn(201, 401, 403, 404, 409);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trades WHERE seller_id = ? AND status IN ('REQUESTED', 'ACCEPTED')",
                Long.class, seller)).isZero();
    }

    private Long user(String nickname) {
        Long id = jdbc.queryForObject("INSERT INTO users (nickname, terms_agreed_at) VALUES (?, now()) RETURNING user_id", Long.class, nickname);
        jdbc.update("INSERT INTO user_identities (user_id, provider, provider_user_id) VALUES (?, 'KAKAO', ?)", id, "kakao-" + id);
        return id;
    }
    private Long image(Long owner, String name) {
        return jdbc.queryForObject("""
                INSERT INTO listing_images (uploader_id, object_key, content_type, file_size, status, purpose)
                VALUES (?, ?, 'image/jpeg', 1024, 'VERIFIED', 'PROFILE') RETURNING image_id
                """, Long.class, owner, "verified/profile-images/" + owner + "/" + name + ".jpg");
    }
    private Long listing(Long owner, String state) {
        Long category = jdbc.queryForObject("SELECT min(category_id) FROM categories", Long.class);
        return jdbc.queryForObject("""
                INSERT INTO listings (seller_id, category_id, title, description, price, item_condition, trade_method, status)
                VALUES (?, ?, '상품', '상품 설명', 100, 'USED', 'DIRECT', ?) RETURNING listing_id
                """, Long.class, owner, category, state);
    }
    private Long trade(Long listing, Long seller, Long buyer, String state) {
        return jdbc.queryForObject("INSERT INTO trades (listing_id, seller_id, buyer_id, status) VALUES (?, ?, ?, ?) RETURNING trade_id",
                Long.class, listing, seller, buyer, state);
    }
    private String bearer(Long id) { return "Bearer " + tokens.issueAccessToken(id, UserRole.USER); }
    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, Map<String, ?> body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body));
    }
}
