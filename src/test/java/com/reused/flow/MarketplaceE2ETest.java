package com.reused.flow;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import com.reused.TestcontainersConfiguration;
import com.reused.auth.client.OAuthProviderClient;
import com.reused.auth.mail.AuthMailSender;
import com.reused.auth.token.JwtTokenProvider;
import com.reused.image.storage.ImageStorage;
import com.reused.user.entity.UserRole;

@Tag("marketplace-e2e")
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MarketplaceE2ETest {
    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtTokenProvider tokens;
    @Autowired @Qualifier("redisContainer") GenericContainer<?> redis;
    @MockitoBean OAuthProviderClient kakaoOAuthClient;
    @MockitoBean AuthMailSender mailSender;
    @MockitoBean ImageStorage imageStorage;

    @Test void actualHttpAndSocketMarketplaceFlow() throws Exception {
        jdbc.execute("TRUNCATE users RESTART IDENTITY CASCADE");
        long adminId = jdbc.queryForObject("INSERT INTO users(nickname,role,terms_agreed_at) VALUES ('검증관리자','ADMIN',now()) RETURNING user_id",Long.class);
        ProcessBuilder builder = new ProcessBuilder("node", "scripts/marketplace-e2e.mjs").redirectErrorStream(true);
        builder.environment().put("CHAT_API_BASE_URL", "http://127.0.0.1:" + port);
        builder.environment().put("CHAT_REDIS_URL", "redis://" + redis.getHost() + ":" + redis.getMappedPort(6379));
        builder.environment().put("MARKET_TEST_ADMIN_TOKEN", tokens.issueAccessToken(adminId,UserRole.ADMIN));
        Process process = builder.start();
        try {
            assertThat(process.waitFor(180, TimeUnit.SECONDS)).as("Marketplace E2E must finish").isTrue();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(process.exitValue()).as(output).isZero();
            assertThat(output).contains("Marketplace E2E passed");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM trades WHERE status='COMPLETED'",Long.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM trades WHERE status='CANCELED'",Long.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM reviews",Long.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs",Long.class)).isEqualTo(2);
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }
}
