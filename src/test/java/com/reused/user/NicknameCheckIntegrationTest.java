package com.reused.user;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

import com.reused.TestcontainersConfiguration;
import com.reused.auth.client.KakaoOAuthClient;
import com.reused.auth.mail.AuthMailSender;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class NicknameCheckIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@MockitoBean
	private KakaoOAuthClient kakaoOAuthClient;

	@MockitoBean
	private AuthMailSender mailSender;

	@BeforeEach
	void resetState() {
		jdbcTemplate.execute(
				"TRUNCATE notification_settings, user_status_histories, user_identities, users RESTART IDENTITY CASCADE");
	}

	@Test
	@DisplayName("쓰이지 않는 닉네임은 available=true, 이미 쓰이는 닉네임은 false다. 인증 없이 호출할 수 있다")
	void reportsAvailability() throws Exception {
		mockMvc.perform(get("/api/v1/users/nickname/check").param("nickname", "재현"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.available").value(true));

		jdbcTemplate.update("INSERT INTO users (nickname, terms_agreed_at) VALUES ('재현', now())");

		mockMvc.perform(get("/api/v1/users/nickname/check").param("nickname", "재현"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.available").value(false));
	}

	@Test
	@DisplayName("2~20자를 벗어나거나 비어 있으면 400 INVALID_INPUT이다")
	void rejectsInvalidLength() throws Exception {
		mockMvc.perform(get("/api/v1/users/nickname/check").param("nickname", "a"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"));
		mockMvc.perform(get("/api/v1/users/nickname/check").param("nickname", "가".repeat(21)))
				.andExpect(status().isBadRequest());
		mockMvc.perform(get("/api/v1/users/nickname/check"))
				.andExpect(status().isBadRequest());
	}

}
