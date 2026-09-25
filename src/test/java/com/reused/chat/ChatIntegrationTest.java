package com.reused.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.reused.TestcontainersConfiguration;
import com.reused.auth.client.OAuthProviderClient;
import com.reused.auth.mail.AuthMailSender;
import com.reused.auth.token.JwtTokenProvider;
import com.reused.common.security.AuthPrincipal;
import com.reused.image.storage.ImageStorage;
import com.reused.user.entity.UserRole;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ChatIntegrationTest {
	private static final String ROOMS = "/api/v1/chat-rooms";
	private static final Instant TIME = Instant.parse("2026-09-25T01:00:00Z");
	@Autowired private MockMvc mvc;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private ObjectMapper mapper;
	@Autowired private JwtTokenProvider tokens;
	@Autowired private ChatService service;
	@Autowired private PlatformTransactionManager transactions;
	@Autowired private RedisConnectionFactory redisConnections;
	@MockitoSpyBean private StringRedisTemplate redis;
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
	@DisplayName("같은 게시글과 구매자의 채팅방은 동시 생성해도 한 개만 존재한다")
	void roomCreationIsIdempotentUnderConcurrency() throws Exception {
		long seller = user("판매자"); long buyer = user("구매자"); long listing = listing(seller, "상품");
		CountDownLatch ready = new CountDownLatch(2); CountDownLatch start = new CountDownLatch(1);
		List<JsonNode> responses = new ArrayList<>();
		try (var pool = Executors.newFixedThreadPool(2)) {
			List<Future<JsonNode>> tasks = new ArrayList<>();
			for (int i = 0; i < 2; i++) tasks.add(pool.submit(() -> {
				ready.countDown(); if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException();
				return response(mvc.perform(json(post(ROOMS), Map.of("listingId", listing)).header("Authorization", bearer(buyer)))
						.andExpect(status().isOk()).andReturn());
			}));
			assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); start.countDown();
			for (var task : tasks) responses.add(task.get(20, TimeUnit.SECONDS));
		}
		assertThat(responses.get(0).get("chatRoomId").asLong()).isEqualTo(responses.get(1).get("chatRoomId").asLong());
		assertThat(responses.stream().filter(r -> r.get("created").asBoolean()).count()).isOne();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_rooms", Integer.class)).isOne();
		long secondBuyer = user("둘구매자");
		mvc.perform(json(post(ROOMS), Map.of("listingId", listing)).header("Authorization", bearer(secondBuyer)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.created").value(true));
		assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_rooms", Integer.class)).isEqualTo(2);
	}

	@Test
	@DisplayName("방 생성은 자기 게시글·차단·숨김·삭제·탈퇴 판매자·정지 요청자를 거절한다")
	void createValidatesMarketplaceRules() throws Exception {
		long seller = user("판매자"); long buyer = user("구매자"); long listing = listing(seller, "상품");
		mvc.perform(json(post(ROOMS), Map.of("listingId", listing)).header("Authorization", bearer(seller)))
				.andExpect(status().isBadRequest());
		jdbc.update("INSERT INTO blocks (blocker_id, blocked_id) VALUES (?, ?)", seller, buyer);
		mvc.perform(json(post(ROOMS), Map.of("listingId", listing)).header("Authorization", bearer(buyer)))
				.andExpect(status().isForbidden());
		jdbc.update("DELETE FROM blocks");
		jdbc.update("UPDATE listings SET status = 'HIDDEN' WHERE listing_id = ?", listing);
		mvc.perform(json(post(ROOMS), Map.of("listingId", listing)).header("Authorization", bearer(buyer)))
				.andExpect(status().isNotFound());
		jdbc.update("UPDATE listings SET status = 'ON_SALE', deleted_at = now(), deleted_by = ? WHERE listing_id = ?", seller, listing);
		mvc.perform(json(post(ROOMS), Map.of("listingId", listing)).header("Authorization", bearer(buyer)))
				.andExpect(status().isNotFound());
		jdbc.update("UPDATE listings SET deleted_at = NULL, deleted_by = NULL WHERE listing_id = ?", listing);
		jdbc.update("UPDATE users SET status = 'WITHDRAWN', withdrawn_at = now() WHERE user_id = ?", seller);
		mvc.perform(json(post(ROOMS), Map.of("listingId", listing)).header("Authorization", bearer(buyer)))
				.andExpect(status().isNotFound());
		jdbc.update("UPDATE users SET status = 'ACTIVE', withdrawn_at = NULL WHERE user_id = ?", seller);
		jdbc.update("UPDATE users SET status = 'SUSPENDED' WHERE user_id = ?", buyer);
		mvc.perform(json(post(ROOMS), Map.of("listingId", listing)).header("Authorization", bearer(buyer)))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("USER_SUSPENDED"));
	}

	@Test
	@DisplayName("메시지 송수신·부분 읽음·발신자 삭제가 양측 화면과 미읽음 수에 반영된다")
	void conversationFlowPreservesSenderReadAndDeleteRules() throws Exception {
		long seller = user("판매자"); long buyer = user("구매자"); long outsider = user("외부회원");
		long room = room(listing(seller, "상품"), seller, buyer, TIME);
		long first = send(room, buyer, "  구매 가능할까요?  ");
		long second = send(room, seller, "가능합니다");
		long third = send(room, buyer, "내일 만나요");
		mvc.perform(get(ROOMS + "/{id}/messages", room).header("Authorization", bearer(seller)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(3))
				.andExpect(jsonPath("$.items[0].messageId").value(third)).andExpect(jsonPath("$.items[0].isMine").value(false))
				.andExpect(jsonPath("$.items[1].isMine").value(true)).andExpect(jsonPath("$.items[2].content").value("구매 가능할까요?"));
		mvc.perform(json(post(ROOMS + "/{id}/read", room), Map.of("lastReadMessageId", first)).header("Authorization", bearer(seller)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.unreadCount").value(1));
		assertThat(jdbc.queryForObject("SELECT read_at FROM messages WHERE message_id = ?", Timestamp.class, first)).isNotNull();
		assertThat(jdbc.queryForObject("SELECT read_at FROM messages WHERE message_id = ?", Timestamp.class, second)).isNull();
		mvc.perform(delete(ROOMS + "/{room}/messages/{id}", room, third).header("Authorization", bearer(seller)))
				.andExpect(status().isForbidden());
		for (int i = 0; i < 2; i++) mvc.perform(delete(ROOMS + "/{room}/messages/{id}", room, third).header("Authorization", bearer(buyer)))
				.andExpect(status().isNoContent());
		mvc.perform(get(ROOMS + "/{id}/messages", room).header("Authorization", bearer(seller)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items[0].isDeleted").value(true))
				.andExpect(jsonPath("$.items[0].content").doesNotExist());
		mvc.perform(get(ROOMS).header("Authorization", bearer(seller)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items[0].unreadCount").value(0))
				.andExpect(jsonPath("$.items[0].lastMessage").doesNotExist());
		mvc.perform(get(ROOMS + "/{id}/messages", room).header("Authorization", bearer(outsider))).andExpect(status().isForbidden());
		mvc.perform(json(post(ROOMS + "/{id}/read", room), Map.of("lastReadMessageId", first)).header("Authorization", bearer(outsider)))
				.andExpect(status().isForbidden());
		assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE type = 'CHAT_RECEIVED'", Integer.class)).isEqualTo(3);
	}

	@Test
	@DisplayName("다른 방의 메시지로 읽거나 삭제할 수 없고 메시지 커서는 방과 사용자에 묶인다")
	void messagesAndReadCursorAreScopedToRoomAndViewer() throws Exception {
		long seller = user("판매자"); long buyer = user("구매자");
		long room = room(listing(seller, "첫상품"), seller, buyer, TIME);
		long other = room(listing(seller, "둘상품"), seller, buyer, TIME);
		long first = send(room, buyer, "첫메시지"); long second = send(room, seller, "둘메시지"); long third = send(room, buyer, "셋메시지");
		long foreign = send(other, buyer, "다른 방 메시지");
		mvc.perform(json(post(ROOMS + "/{id}/read", room), Map.of("lastReadMessageId", foreign)).header("Authorization", bearer(seller)))
				.andExpect(status().isNotFound());
		mvc.perform(delete(ROOMS + "/{room}/messages/{id}", room, foreign).header("Authorization", bearer(buyer)))
				.andExpect(status().isNotFound());
		var page = response(mvc.perform(get(ROOMS + "/{id}/messages", room).param("size", "2").header("Authorization", bearer(buyer)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items[0].messageId").value(third))
				.andExpect(jsonPath("$.items[1].messageId").value(second)).andExpect(jsonPath("$.hasNext").value(true)).andReturn());
		String cursor = page.get("nextCursor").asString();
		mvc.perform(get(ROOMS + "/{id}/messages", room).param("cursor", cursor).header("Authorization", bearer(buyer)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].messageId").value(first)).andExpect(jsonPath("$.hasNext").value(false));
		mvc.perform(get(ROOMS + "/{id}/messages", other).param("cursor", cursor).header("Authorization", bearer(buyer)))
				.andExpect(status().isBadRequest());
		mvc.perform(get(ROOMS + "/{id}/messages", room).param("cursor", cursor).header("Authorization", bearer(seller)))
				.andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("채팅방은 마지막 활동순과 ID 커서로 빈 방까지 조회하고 차단 관계는 양방향 제외한다")
	void roomPagingIncludesEmptyRoomsAndExcludesBlocks() throws Exception {
		long seller = user("판매자"); long buyer = user("구매자"); long otherSeller = user("다른판매자");
		long first = room(listing(seller, "첫상품"), seller, buyer, TIME);
		long second = room(listing(seller, "둘상품"), seller, buyer, TIME);
		long thirdListing = listing(seller, "셋상품");
		long third = room(thirdListing, seller, buyer, TIME);
		long blocked = room(listing(otherSeller, "차단상품"), otherSeller, buyer, TIME.plusSeconds(1));
		jdbc.update("INSERT INTO blocks (blocker_id, blocked_id) VALUES (?, ?)", otherSeller, buyer);
		jdbc.update("""
				INSERT INTO listing_images (listing_id, uploader_id, object_key, content_type, file_size, status, display_order)
				VALUES (?, ?, 'chat/thumb.jpg', 'image/jpeg', 100, 'VERIFIED', 0)
				""", thirdListing, seller);
		var page = response(mvc.perform(get(ROOMS).param("size", "2").header("Authorization", bearer(buyer)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items[0].chatRoomId").value(third))
				.andExpect(jsonPath("$.items[0].lastMessageAt").doesNotExist())
				.andExpect(jsonPath("$.items[0].listing.thumbnailUrl").value("https://images.example.test/chat/thumb.jpg"))
				.andExpect(jsonPath("$.items[1].chatRoomId").value(second)).andExpect(jsonPath("$.hasNext").value(true)).andReturn());
		mvc.perform(get(ROOMS).param("cursor", page.get("nextCursor").asString()).header("Authorization", bearer(buyer)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].chatRoomId").value(first)).andExpect(jsonPath("$.hasNext").value(false));
		send(first, seller, "새 메시지");
		mvc.perform(get(ROOMS).header("Authorization", bearer(buyer)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items[0].chatRoomId").value(first))
				.andExpect(jsonPath("$.items[0].unreadCount").value(1));
		mvc.perform(get(ROOMS + "/{id}/subscription", blocked).header("Authorization", bearer(buyer))).andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("연결과 구독은 현재 회원 권한·당사자·차단을 재검증하고 정지 후 전송을 거절한다")
	void sessionsAndSubscriptionsRevalidateCurrentIdentity() throws Exception {
		long seller = user("판매자"); long buyer = user("구매자"); long outsider = user("외부회원");
		long room = room(listing(seller, "상품"), seller, buyer, TIME);
		mvc.perform(get("/api/v1/chat/session")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/v1/chat/session").header("Authorization", bearer(buyer)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.userId").value(buyer));
		mvc.perform(get(ROOMS + "/{id}/subscription", room).header("Authorization", bearer(buyer)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.userId").value(buyer)).andExpect(jsonPath("$.chatRoomId").value(room));
		mvc.perform(get(ROOMS + "/{id}/subscription", room).header("Authorization", bearer(outsider))).andExpect(status().isForbidden());
		jdbc.update("INSERT INTO blocks (blocker_id, blocked_id) VALUES (?, ?)", buyer, seller);
		mvc.perform(get(ROOMS + "/{id}/subscription", room).header("Authorization", bearer(seller))).andExpect(status().isForbidden());
		mvc.perform(json(post(ROOMS + "/{id}/messages", room), Map.of("content", "차단 이후 전송"))
				.header("Authorization", bearer(seller))).andExpect(status().isForbidden());
		jdbc.update("DELETE FROM blocks");
		jdbc.update("UPDATE users SET status = 'SUSPENDED' WHERE user_id = ?", buyer);
		mvc.perform(json(post(ROOMS + "/{id}/messages", room), Map.of("content", "정지 이후 전송"))
				.header("Authorization", bearer(buyer))).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("USER_SUSPENDED"));
		mvc.perform(get(ROOMS + "/{id}/messages", room).header("Authorization", bearer(buyer))).andExpect(status().isOk());
		jdbc.update("UPDATE users SET status = 'ACTIVE', role = 'ADMIN' WHERE user_id = ?", buyer);
		mvc.perform(get("/api/v1/chat/session").header("Authorization", bearer(buyer))).andExpect(status().isForbidden());
		mvc.perform(get(ROOMS).header("Authorization", "Bearer " + tokens.issueAccessToken(buyer, UserRole.ADMIN))).andExpect(status().isForbidden());
		jdbc.update("UPDATE users SET role = 'USER', status = 'WITHDRAWN', withdrawn_at = now() WHERE user_id = ?", buyer);
		mvc.perform(get("/api/v1/chat/session").header("Authorization", bearer(buyer))).andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("Redis에는 커밋된 메시지·읽음·삭제만 발행하고 롤백된 메시지와 알림은 남지 않는다")
	void redisDeliversCommittedEventsButNothingFromRollback() throws Exception {
		long seller = user("판매자"); long buyer = user("구매자"); long room = room(listing(seller, "상품"), seller, buyer, TIME);
		BlockingQueue<String> received = new LinkedBlockingQueue<>();
		var listener = new RedisMessageListenerContainer();
		listener.setConnectionFactory(redisConnections);
		listener.addMessageListener((message, pattern) -> received.add(new String(message.getBody(), StandardCharsets.UTF_8)),
				new ChannelTopic("reused:chat:room:" + room));
		listener.afterPropertiesSet(); listener.start();
		try {
			var transaction = new TransactionTemplate(transactions);
			ChatResponses.Message saved = transaction.execute(status -> {
				var message = service.send(new AuthPrincipal(buyer, UserRole.USER), room, "커밋 메시지");
				assertThat(received).isEmpty();
				return message;
			});
			JsonNode event = mapper.readTree(received.poll(5, TimeUnit.SECONDS));
			assertThat(event.get("event").asString()).isEqualTo("message");
			assertThat(event.get("chatRoomId").asLong()).isEqualTo(room);
			assertThat(event.get("data").get("senderId").asLong()).isEqualTo(buyer);
			assertThat(event.get("data").get("messageId").asLong()).isEqualTo(saved.messageId());
			assertThat(jdbc.queryForObject("SELECT count(*) FROM messages", Integer.class)).isOne();
			service.read(new AuthPrincipal(seller, UserRole.USER), room, saved.messageId());
			assertThat(mapper.readTree(received.poll(5, TimeUnit.SECONDS)).get("event").asString()).isEqualTo("read");
			service.delete(new AuthPrincipal(buyer, UserRole.USER), room, saved.messageId());
			assertThat(mapper.readTree(received.poll(5, TimeUnit.SECONDS)).get("event").asString()).isEqualTo("message_deleted");
			transaction.executeWithoutResult(status -> {
				service.send(new AuthPrincipal(seller, UserRole.USER), room, "롤백 메시지");
				status.setRollbackOnly();
			});
			assertThat(received.poll(200, TimeUnit.MILLISECONDS)).isNull();
			assertThat(jdbc.queryForObject("SELECT count(*) FROM messages", Integer.class)).isOne();
			assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications", Integer.class)).isOne();
		}
		finally { listener.stop(); listener.destroy(); }
	}

	@Test
	@DisplayName("실시간 Redis 전달 실패도 저장된 메시지와 알림을 되돌리지 않는다")
	void redisFailureDoesNotUndoDurableMessage() throws Exception {
		long seller = user("판매자"); long buyer = user("구매자"); long room = room(listing(seller, "상품"), seller, buyer, TIME);
		doThrow(new RedisConnectionFailureException("test outage")).when(redis).convertAndSend(eq("reused:chat:room:" + room), any());
		long message = send(room, buyer, "저장은 성공");
		mvc.perform(get(ROOMS + "/{id}/messages", room).header("Authorization", bearer(seller)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items[0].messageId").value(message))
				.andExpect(jsonPath("$.items[0].content").value("저장은 성공"));
		assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications", Integer.class)).isOne();
	}

	@Test
	@DisplayName("양측 동시 송신은 두 메시지를 보존하고 마지막 활동 시각과 미읽음 수를 유지한다")
	void concurrentSendsPreserveBothMessagesAndLatestActivity() throws Exception {
		long seller = user("판매자"); long buyer = user("구매자");
		long room = room(listing(seller, "동시 대화"), seller, buyer, TIME);
		CountDownLatch ready = new CountDownLatch(2); CountDownLatch start = new CountDownLatch(1);
		List<Long> ids = new ArrayList<>();
		try (var pool = Executors.newFixedThreadPool(2)) {
			List<Future<Long>> tasks = new ArrayList<>();
			for (long sender : List.of(seller, buyer)) tasks.add(pool.submit(() -> {
				ready.countDown(); if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException();
				return send(room, sender, "동시 메시지 " + sender);
			}));
			assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); start.countDown();
			for (var task : tasks) ids.add(task.get(20, TimeUnit.SECONDS));
		}
		assertThat(ids).doesNotHaveDuplicates();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM messages WHERE chat_room_id = ?", Integer.class, room)).isEqualTo(2);
		assertThat(jdbc.queryForObject("SELECT last_message_at FROM chat_rooms WHERE chat_room_id = ?", Timestamp.class, room))
				.isEqualTo(jdbc.queryForObject("SELECT max(created_at) FROM messages WHERE chat_room_id = ?", Timestamp.class, room));
		for (long viewer : List.of(seller, buyer)) mvc.perform(get(ROOMS).header("Authorization", bearer(viewer)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items[0].unreadCount").value(1));
	}

	@Test
	@DisplayName("잘못된 입력·페이지·커서와 외부 사용자 전송은 저장되지 않는다")
	void invalidInputsAndNonParticipantsCannotWrite() throws Exception {
		long seller = user("판매자"); long buyer = user("구매자"); long outsider = user("외부회원");
		long room = room(listing(seller, "상품"), seller, buyer, TIME);
		for (String content : List.of("", "   ", "a".repeat(1001)))
			mvc.perform(json(post(ROOMS + "/{id}/messages", room), Map.of("content", content)).header("Authorization", bearer(buyer)))
					.andExpect(status().isBadRequest());
		mvc.perform(json(post(ROOMS + "/{id}/messages", room), Map.of()).header("Authorization", bearer(buyer))).andExpect(status().isBadRequest());
		mvc.perform(json(post(ROOMS + "/{id}/messages", room), Map.of("content", "외부 메시지")).header("Authorization", bearer(outsider)))
				.andExpect(status().isForbidden());
		mvc.perform(json(post(ROOMS), Map.of("listingId", 0)).header("Authorization", bearer(buyer))).andExpect(status().isBadRequest());
		mvc.perform(json(post(ROOMS + "/{id}/read", room), Map.of("lastReadMessageId", 0)).header("Authorization", bearer(buyer)))
				.andExpect(status().isBadRequest());
		for (String size : List.of("0", "101", "invalid")) {
			mvc.perform(get(ROOMS).param("size", size).header("Authorization", bearer(buyer))).andExpect(status().isBadRequest());
			mvc.perform(get(ROOMS + "/{id}/messages", room).param("size", size).header("Authorization", bearer(buyer)))
					.andExpect(status().isBadRequest());
		}
		for (String cursor : List.of("invalid", "x".repeat(1100))) {
			mvc.perform(get(ROOMS).param("cursor", cursor).header("Authorization", bearer(buyer))).andExpect(status().isBadRequest());
			mvc.perform(get(ROOMS + "/{id}/messages", room).param("cursor", cursor).header("Authorization", bearer(buyer)))
					.andExpect(status().isBadRequest());
		}
		assertThat(jdbc.queryForObject("SELECT count(*) FROM messages", Integer.class)).isZero();
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
	private long room(long listing, long seller, long buyer, Instant created) {
		return jdbc.queryForObject("INSERT INTO chat_rooms (listing_id, seller_id, buyer_id, created_at) VALUES (?, ?, ?, ?) RETURNING chat_room_id",
				Long.class, listing, seller, buyer, Timestamp.from(created));
	}
	private long send(long room, long sender, String content) throws Exception {
		return response(mvc.perform(json(post(ROOMS + "/{id}/messages", room), Map.of("content", content))
				.header("Authorization", bearer(sender))).andExpect(status().isCreated())
				.andExpect(jsonPath("$.isMine").value(true)).andReturn()).get("messageId").asLong();
	}
	private String bearer(long user) { return "Bearer " + tokens.issueAccessToken(user, UserRole.USER); }
	private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder, Map<String, ?> body) {
		return builder.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body));
	}
	private JsonNode response(MvcResult result) throws Exception { return mapper.readTree(result.getResponse().getContentAsString()); }
}
