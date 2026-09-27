package com.reused.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.reused.auth.client.OAuthProviderClient;
import com.reused.auth.mail.AuthMailSender;
import com.reused.image.storage.ImageStorage;

@Import(LocalDemoSeederIntegrationTest.DemoContainers.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local-demo")
@TestPropertySource(properties = { "app.demo.seed=true", "app.demo.password=DemoOnly26!" })
class LocalDemoSeederIntegrationTest {

	@Autowired private JdbcTemplate jdbc;
	@Autowired private LocalDemoSeeder seeder;
	@Autowired private MockMvc mvc;
	@Autowired private ObjectMapper mapper;
	@MockitoBean private OAuthProviderClient kakaoClient;
	@MockitoBean private AuthMailSender mailSender;
	@MockitoBean private ImageStorage imageStorage;

	@Test
	void seedsConnectedMarketplaceOnceAndAllowsRealEmailLogin() throws Exception {
		assertCount("users", 3);
		assertCount("user_identities", 3);
		assertCount("listings", 2);
		assertCount("trades", 1);
		assertCount("chat_rooms", 1);
		assertCount("messages", 1);
		assertCount("community_posts", 1);
		assertCount("community_comments", 1);
		assertCount("reports", 1);
		assertCount("notices", 1);
		assertThat(jdbc.queryForObject("SELECT comment_count FROM community_posts", Integer.class)).isOne();
		assertThat(jdbc.queryForObject("SELECT password_hash FROM user_identities WHERE email = ?", String.class,
				"demo-seller@reused.invalid")).startsWith("$argon2id$");

		JsonNode login = mapper.readTree(mvc.perform(post("/api/v1/auth/email/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content(mapper.writeValueAsString(Map.of("email", "demo-buyer@reused.invalid",
						"password", "DemoOnly26!"))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accessToken").isNotEmpty())
				.andReturn().getResponse().getContentAsString());
		String bearer = "Bearer " + login.get("accessToken").asString();
		mvc.perform(get("/api/v1/listings")).andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(2));
		mvc.perform(get("/api/v1/community/posts")).andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(1));
		mvc.perform(get("/api/v1/chat-rooms").header("Authorization", bearer)).andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(1));
		mvc.perform(get("/api/v1/reports/me").header("Authorization", bearer)).andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(1));

		String hash = jdbc.queryForObject("SELECT password_hash FROM user_identities WHERE email = ?", String.class,
				"demo-seller@reused.invalid");
		seeder.run(new DefaultApplicationArguments(new String[0]));
		assertCount("users", 3);
		assertCount("listings", 2);
		assertCount("trades", 1);
		assertCount("messages", 1);
		assertCount("community_comments", 1);
		assertCount("reports", 1);
		assertThat(jdbc.queryForObject("SELECT comment_count FROM community_posts", Integer.class)).isOne();
		assertThat(jdbc.queryForObject("SELECT password_hash FROM user_identities WHERE email = ?", String.class,
				"demo-seller@reused.invalid")).isEqualTo(hash);
	}

	@Test
	void targetGuardRejectsRemoteAndOrdinaryDatabases() {
		assertThat(LocalDemoSeeder.isAllowedTarget("jdbc:postgresql://localhost:5432/reused_demo_test",
				"reused_demo_test")).isTrue();
		assertThat(LocalDemoSeeder.isAllowedTarget("jdbc:postgresql://db.example.com:5432/reused_demo_test",
				"reused_demo_test")).isFalse();
		assertThat(LocalDemoSeeder.isAllowedTarget("jdbc:postgresql://localhost:5432/reused",
				"reused")).isFalse();
	}

	private void assertCount(String table, int expected) {
		assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class)).isEqualTo(expected);
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class DemoContainers {
		@Bean
		@ServiceConnection
		PostgreSQLContainer postgresContainer() {
			return new PostgreSQLContainer(DockerImageName.parse("postgres:18.6-alpine"))
					.withDatabaseName("reused_demo_test")
					.withInitScripts("schema/001_init.sql", "schema/002_seed_categories.sql", "schema/003_profile_images.sql");
		}

		@Bean
		@ServiceConnection(name = "redis")
		GenericContainer<?> redisContainer() {
			return new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
		}
	}
}
