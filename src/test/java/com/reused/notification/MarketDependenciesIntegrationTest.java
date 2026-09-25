package com.reused.notification;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.Map;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import com.reused.TestcontainersConfiguration;
import com.reused.auth.client.OAuthProviderClient;
import com.reused.auth.mail.AuthMailSender;
import com.reused.auth.token.JwtTokenProvider;
import com.reused.audit.service.AuditService;
import com.reused.block.BlockService;
import com.reused.image.storage.ImageStorage;
import com.reused.notification.service.NotificationService;
import com.reused.user.entity.UserRole;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class MarketDependenciesIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired JwtTokenProvider tokens;
    @Autowired NotificationService notifications;
    @Autowired BlockService blocks;
    @Autowired AuditService audit;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoBean OAuthProviderClient kakaoOAuthClient;
    @MockitoBean AuthMailSender mailSender;
    @MockitoBean ImageStorage imageStorage;
    long first, second;
    @BeforeEach void setup() {
        jdbc.execute("TRUNCATE users RESTART IDENTITY CASCADE");
        first = user("첫회원"); second = user("둘회원");
    }
    @Test void blocksAreIdempotentPrivateAndBidirectional() throws Exception {
        String body = mapper.writeValueAsString(Map.of("userId", second));
        String result = mvc.perform(post("/api/v1/blocks").header("Authorization", auth(first))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk())
                .andExpect(jsonPath("$.blocked").value(true)).andReturn().getResponse().getContentAsString();
        long id = mapper.readTree(result).get("blockId").asLong();
        mvc.perform(post("/api/v1/blocks").header("Authorization", auth(first))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk())
                .andExpect(jsonPath("$.blockId").value(id));
        assertThat(blocks.eitherDirection(first,second)).isTrue();
        assertThat(blocks.eitherDirection(second,first)).isTrue();
        mvc.perform(get("/api/v1/blocks").header("Authorization",auth(second)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
        mvc.perform(get("/api/v1/blocks").header("Authorization",auth(first)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].blockedUser.userId").value(second));
        for (int i=0;i<2;i++) mvc.perform(delete("/api/v1/blocks/{id}",second).header("Authorization",auth(first)))
                .andExpect(status().isNoContent());
        assertThat(blocks.eitherDirection(first,second)).isFalse();
    }
    @Test void invalidBlocksAndStaleAccountsAreRejected() throws Exception {
        mvc.perform(post("/api/v1/blocks").header("Authorization",auth(first))
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("userId",first))))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/blocks").header("Authorization",auth(first))
                .contentType(MediaType.APPLICATION_JSON).content("{\"userId\":999999}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/notifications")).andExpect(status().isUnauthorized());
        String stale = auth(first);
        jdbc.update("UPDATE users SET status='WITHDRAWN', withdrawn_at=now() WHERE user_id=?",first);
        mvc.perform(get("/api/v1/notifications").header("Authorization",stale)).andExpect(status().isUnauthorized());
    }
    @Test void notificationsRespectPartialSettingsAndAccountStatus() throws Exception {
        mvc.perform(get("/api/v1/notifications/settings").header("Authorization",auth(first)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.tradeEnabled").value(true));
        mvc.perform(patch("/api/v1/notifications/settings").header("Authorization",auth(first))
                .contentType(MediaType.APPLICATION_JSON).content("{\"chatEnabled\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.tradeEnabled").value(true))
                .andExpect(jsonPath("$.chatEnabled").value(false));
        notifications.createFor(first,"CHAT_RECEIVED","메시지","비공개 내용","CHAT_ROOM",1L);
        notifications.createFor(first,"TRADE_REQUESTED","거래 요청","요청 도착","TRADE",1L);
        jdbc.update("UPDATE users SET status='WITHDRAWN', withdrawn_at=now() WHERE user_id=?",second);
        notifications.createFor(second,"TRADE_REQUESTED","거래 요청","요청 도착","TRADE",1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications",Long.class)).isEqualTo(1);
        mvc.perform(get("/api/v1/notifications/unread-count").header("Authorization",auth(first)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.count").value(1));
    }
    @Test void notificationPaginationOwnershipAndReadAreCorrect() throws Exception {
        for(int i=0;i<3;i++) notifications.createFor(first,"TRADE_REQUESTED","요청","도착","TRADE",1L);
        notifications.createFor(second,"CHAT_RECEIVED","메시지","도착","CHAT_ROOM",1L);
        String result = mvc.perform(get("/api/v1/notifications").param("size","2").header("Authorization",auth(first)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.hasNext").value(true)).andReturn().getResponse().getContentAsString();
        String cursor = mapper.readTree(result).get("nextCursor").asString();
        mvc.perform(get("/api/v1/notifications").param("size","2").param("cursor",cursor).header("Authorization",auth(first)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1));
        mvc.perform(get("/api/v1/notifications").param("cursor",cursor).header("Authorization",auth(second)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/notifications").param("size","0").header("Authorization",auth(first)))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/notifications/1/read").header("Authorization",auth(second)))
                .andExpect(status().isForbidden());
        for(int i=0;i<2;i++) mvc.perform(post("/api/v1/notifications/1/read").header("Authorization",auth(first)))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/notifications/read-all").header("Authorization",auth(first)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.readCount").value(2));
        mvc.perform(get("/api/v1/notifications").param("unreadOnly","true").header("Authorization",auth(first)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id=? AND read_at IS NULL",Long.class,second)).isEqualTo(1);
    }
    @Test void auditAndNotificationRollbackWithSourceAction() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        assertThatThrownBy(()->tx.execute(status->{
            audit.record(first,"LISTING_HIDE","LISTING",1L,Map.of("reason","검증"));
            notifications.createFor(first,"TRADE_REQUESTED","요청","도착","TRADE",1L);
            throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications",Long.class)).isZero();
        tx.execute(status->{ audit.record(first,"LISTING_HIDE","LISTING",1L,Map.of("reason","검증")); return null; });
        assertThat(jdbc.queryForObject("SELECT detail->>'reason' FROM audit_logs",String.class)).isEqualTo("검증");
    }
    @Test void fullTradeFlowCreatesRecipientNotifications() throws Exception {
        long listing = jdbc.queryForObject("""
                INSERT INTO listings(seller_id,category_id,title,description,price,item_condition,trade_method)
                VALUES (?,(SELECT category_id FROM categories LIMIT 1),'상품','설명',1000,'LIKE_NEW','BOTH') RETURNING listing_id
                """,Long.class,first);
        String result = mvc.perform(post("/api/v1/trades").header("Authorization",auth(second))
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("listingId",listing))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long trade = mapper.readTree(result).get("tradeId").asLong();
        mvc.perform(post("/api/v1/trades/{id}/accept",trade).header("Authorization",auth(first))).andExpect(status().isOk());
        mvc.perform(post("/api/v1/trades/{id}/complete",trade).header("Authorization",auth(second))).andExpect(status().isOk());
        assertThat(jdbc.queryForList("SELECT type FROM notifications ORDER BY notification_id",String.class))
                .containsExactly("TRADE_REQUESTED","TRADE_ACCEPTED","TRADE_COMPLETED","TRADE_COMPLETED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id=?",Long.class,first)).isEqualTo(2);
    }
    long user(String nickname) { return jdbc.queryForObject("INSERT INTO users(nickname,terms_agreed_at) VALUES (?,now()) RETURNING user_id",Long.class,nickname); }
    String auth(long id) { return "Bearer " + tokens.issueAccessToken(id,UserRole.USER); }
}
