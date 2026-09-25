package com.reused.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.reused.TestcontainersConfiguration;
import com.reused.auth.client.OAuthProviderClient;
import com.reused.auth.config.AuthProperties;
import com.reused.auth.mail.AuthMailSender;
import com.reused.auth.token.JwtTokenProvider;
import com.reused.image.storage.ImageStorage;
import com.reused.user.entity.AuthProvider;
import com.reused.user.entity.UserRole;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class MarketRegressionIntegrationTest {
	@Autowired private MockMvc mvc;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private ObjectMapper mapper;
	@Autowired private JwtTokenProvider tokens;
	@Autowired private AuthProperties auth;
	@Autowired private PlatformTransactionManager transactions;
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
	@DisplayName("공개 조회는 무인증을 허용하되 제공된 잘못된·만료된·가입용 토큰은 401이다")
	void publicEndpointsRejectInvalidProvidedCredentials() throws Exception {
		long seller = user("판매자"); long listing = listing(seller, "상품");
		List<String> publicPaths = List.of("/api/v1/categories", "/api/v1/listings", "/api/v1/listings/" + listing,
				"/api/v1/users/" + seller + "/profile", "/api/v1/users/" + seller + "/reviews",
				"/api/v1/users/" + seller + "/listings");
		List<String> invalid = List.of("Bearer invalid", "Bearer ", "Basic unsupported",
				"Bearer " + expiredAccessToken(seller),
				"Bearer " + tokens.issueSignupToken(AuthProvider.KAKAO, "signup-only"));
		for (String path : publicPaths) {
			mvc.perform(get(path)).andExpect(status().isOk());
			for (String authorization : invalid) {
				mvc.perform(get(path).header("Authorization", authorization)).andExpect(status().isUnauthorized())
						.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
						.andExpect(header().string("WWW-Authenticate", "Bearer"));
			}
		}
		mvc.perform(get("/api/v1/listings").header("Authorization", bearer(seller))).andExpect(status().isOk());
		mvc.perform(get("/api/v1/wishes").header("Authorization", "bearer " + tokens.issueAccessToken(seller, UserRole.USER)))
				.andExpect(status().isOk());
	}

	@Test
	@DisplayName("탈퇴 판매자의 상품은 공개 탐색에서 제외하고 기존 거래·채팅 기록은 보존한다")
	void withdrawnSellerLeavesHistoryButNoPublicMarketplaceListing() throws Exception {
		long departed = user("탈퇴할판매자"); long active = user("현재판매자"); long buyer = user("구매자");
		long unavailable = listing(departed, "탈퇴 판매자의 상품"); long visible = listing(active, "판매중 상품");
		long trade = completedTrade(unavailable, departed, buyer);
		long room = jdbc.queryForObject("""
				INSERT INTO chat_rooms (listing_id, seller_id, buyer_id, trade_id) VALUES (?, ?, ?, ?) RETURNING chat_room_id
				""", Long.class, unavailable, departed, buyer, trade);
		mvc.perform(post("/api/v1/listings/{id}/wish", unavailable).header("Authorization", bearer(buyer))).andExpect(status().isOk());
		mvc.perform(post("/api/v1/listings/{id}/wish", visible).header("Authorization", bearer(buyer))).andExpect(status().isOk());
		jdbc.update("UPDATE users SET status = 'WITHDRAWN', withdrawn_at = now(), nickname = '탈퇴회원' WHERE user_id = ?", departed);
		mvc.perform(get("/api/v1/listings")).andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].listingId").value(visible));
		mvc.perform(get("/api/v1/listings/{id}", unavailable)).andExpect(status().isNotFound());
		mvc.perform(get("/api/v1/wishes").header("Authorization", bearer(buyer))).andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].listingId").value(visible));
		mvc.perform(post("/api/v1/listings/{id}/wish", unavailable).header("Authorization", bearer(buyer))).andExpect(status().isNotFound());
		mvc.perform(delete("/api/v1/listings/{id}/wish", unavailable).header("Authorization", bearer(buyer)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.wishCount").value(0));
		mvc.perform(get("/api/v1/trades/{id}", trade).header("Authorization", bearer(buyer)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.listing.listingId").value(unavailable));
		mvc.perform(get("/api/v1/chat-rooms").header("Authorization", bearer(buyer)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items[0].chatRoomId").value(room));
		assertThat(jdbc.queryForObject("SELECT count(*) FROM listings WHERE listing_id = ?", Integer.class, unavailable)).isOne();
		assertThat(jdbc.queryForObject("SELECT view_count FROM listings WHERE listing_id = ?", Integer.class, unavailable)).isZero();
	}

	@Test
	@DisplayName("소유자는 숨김 게시글을 수정할 수 있고 공개 노출은 관리자 복구 전까지 차단한다")
	void ownerCanCorrectHiddenListingBeforeAdminRestoresIt() throws Exception {
		long seller = user("판매자"); long outsider = user("외부회원"); long admin = user("관리자");
		jdbc.update("UPDATE users SET role = 'ADMIN' WHERE user_id = ?", admin);
		String adminBearer = "Bearer " + tokens.issueAccessToken(admin, UserRole.ADMIN);
		long listing = listing(seller, "수정할 상품");
		mvc.perform(json(patch("/api/v1/admin/listings/{id}/status", listing), Map.of("status", "HIDDEN", "reason", "내용 보완 필요"))
				.header("Authorization", adminBearer)).andExpect(status().isOk());
		mvc.perform(json(patch("/api/v1/listings/{id}", listing), Map.of("title", "보완한 상품", "description", "정확한 설명으로 보완했습니다."))
				.header("Authorization", bearer(seller))).andExpect(status().isOk())
				.andExpect(jsonPath("$.title").value("보완한 상품")).andExpect(jsonPath("$.status").value("HIDDEN"))
				.andExpect(jsonPath("$.isMine").value(true));
		mvc.perform(json(patch("/api/v1/listings/{id}", listing), Map.of("title", "외부인 수정"))
				.header("Authorization", bearer(outsider))).andExpect(status().isForbidden());
		mvc.perform(get("/api/v1/listings/{id}", listing)).andExpect(status().isNotFound());
		mvc.perform(get("/api/v1/listings/{id}", listing).header("Authorization", bearer(seller))).andExpect(status().isNotFound());
		mvc.perform(get("/api/v1/listings")).andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
		mvc.perform(get("/api/v1/admin/listings").header("Authorization", adminBearer)).andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].title").value("보완한 상품")).andExpect(jsonPath("$.items[0].status").value("HIDDEN"));
		mvc.perform(json(patch("/api/v1/admin/listings/{id}/status", listing), Map.of("status", "RESTORE", "reason", "보완 확인"))
				.header("Authorization", adminBearer)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ON_SALE"));
		mvc.perform(get("/api/v1/listings/{id}", listing)).andExpect(status().isOk())
				.andExpect(jsonPath("$.title").value("보완한 상품"))
				.andExpect(jsonPath("$.description").value("정확한 설명으로 보완했습니다."));
	}

	@Test
	@DisplayName("후기 작성은 탈퇴 트랜잭션을 기다린 후 현재 상태를 검증하여 저장을 거절한다")
	void reviewWaitsForWithdrawalBeforeLoadingActor() throws Exception {
		long seller = user("판매자"); long buyer = user("탈퇴할구매자");
		long trade = completedTrade(listing(seller, "상품"), seller, buyer);
		String authorization = bearer(buyer);
		AtomicReference<Future<MvcResult>> pending = new AtomicReference<>();
		CountDownLatch requestStarted = new CountDownLatch(1);
		try (var executor = Executors.newSingleThreadExecutor()) {
			new TransactionTemplate(transactions).executeWithoutResult(tx -> {
				// Non-key update mirrors the account-state lock held by the withdrawal endpoint.
				jdbc.update("UPDATE users SET status = 'WITHDRAWN', withdrawn_at = now() WHERE user_id = ?", buyer);
				Future<MvcResult> future = executor.submit(() -> {
					requestStarted.countDown();
					return mvc.perform(json(post("/api/v1/reviews"), Map.of("tradeId", trade, "rating", 5))
							.header("Authorization", authorization)).andReturn();
				});
				pending.set(future);
				try { assertThat(requestStarted.await(10, TimeUnit.SECONDS)).isTrue(); }
				catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
				assertThatThrownBy(() -> future.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
			});
			MvcResult result = pending.get().get(10, TimeUnit.SECONDS);
			assertThat(result.getResponse().getStatus()).isEqualTo(401);
			assertThat(mapper.readTree(result.getResponse().getContentAsString()).get("code").asString()).isEqualTo("UNAUTHENTICATED");
		}
		assertThat(jdbc.queryForObject("SELECT count(*) FROM reviews", Integer.class)).isZero();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications", Integer.class)).isZero();
	}

	@Test
	@DisplayName("완료 거래 상대방 탈퇴 후에도 활성 회원은 후기를 한 번만 남길 수 있다")
	void reviewOfCompletedTradeStillAllowsWithdrawnCounterparty() throws Exception {
		long seller = user("탈퇴판매자"); long buyer = user("구매자");
		long trade = completedTrade(listing(seller, "완료상품"), seller, buyer);
		jdbc.update("UPDATE users SET status = 'WITHDRAWN', withdrawn_at = now() WHERE user_id = ?", seller);
		mvc.perform(json(post("/api/v1/reviews"), Map.of("tradeId", trade, "rating", 5)).header("Authorization", bearer(buyer)))
				.andExpect(status().isCreated());
		mvc.perform(json(post("/api/v1/reviews"), Map.of("tradeId", trade, "rating", 4)).header("Authorization", bearer(buyer)))
				.andExpect(status().isConflict());
		assertThat(jdbc.queryForObject("SELECT count(*) FROM reviews", Integer.class)).isOne();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications", Integer.class)).isZero();
	}

	private long user(String nickname) {
		return jdbc.queryForObject("INSERT INTO users (nickname, terms_agreed_at) VALUES (?, now()) RETURNING user_id", Long.class, nickname);
	}
	private long listing(long seller, String title) {
		Long category = jdbc.queryForObject("SELECT category_id FROM categories WHERE name = '디지털기기'", Long.class);
		return jdbc.queryForObject("""
				INSERT INTO listings (seller_id, category_id, title, description, price, item_condition, trade_method)
				VALUES (?, ?, ?, '상품 설명', 10000, 'LIKE_NEW', 'BOTH') RETURNING listing_id
				""", Long.class, seller, category, title);
	}
	private long completedTrade(long listing, long seller, long buyer) {
		jdbc.update("UPDATE listings SET status = 'COMPLETED' WHERE listing_id = ?", listing);
		return jdbc.queryForObject("""
				INSERT INTO trades (listing_id, seller_id, buyer_id, status, accepted_at, completed_at)
				VALUES (?, ?, ?, 'COMPLETED', now(), now()) RETURNING trade_id
				""", Long.class, listing, seller, buyer);
	}
	private String bearer(long user) { return "Bearer " + tokens.issueAccessToken(user, UserRole.USER); }
	private String expiredAccessToken(long user) {
		Instant now = Instant.now();
		JwtClaimsSet claims = JwtClaimsSet.builder().subject(Long.toString(user))
				.claim("typ", "access").claim("role", UserRole.USER.name())
				.issuedAt(now.minus(Duration.ofMinutes(15))).expiresAt(now.minus(Duration.ofMinutes(5))).build();
		var key = new SecretKeySpec(auth.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
		var encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
		return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
	}
	private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder, Map<String, ?> body) {
		return builder.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body));
	}
}
