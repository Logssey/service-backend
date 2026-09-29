package com.reused.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.reused.TestcontainersConfiguration;
import com.reused.auth.client.OAuthProviderClient;

/**
 * local 프로파일 없이 app.kakao.stub=true만 켠 경우. 대역이 뜨지 않고 카카오 로그인이 막혀야 한다.
 * 운영에서 설정 실수로 검증 없는 로그인이 열리는 것을 막는 방어선이다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "app.kakao.stub=true")
@AutoConfigureMockMvc
class KakaoStubGuardIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ApplicationContext context;

	@Test
	@DisplayName("프로파일 없이 stub만 켜면 어떤 카카오 클라이언트도 뜨지 않고 로그인은 404다")
	void stubWithoutLocalProfileFailsClosed() throws Exception {
		assertThat(context.getBeansOfType(OAuthProviderClient.class)).isEmpty();

		mockMvc.perform(post("/api/v1/auth/oauth/kakao").contentType(MediaType.APPLICATION_JSON)
						.content("{\"code\":\"123\",\"redirectUri\":\"http://localhost/cb\"}"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_FOUND"));
	}

}
