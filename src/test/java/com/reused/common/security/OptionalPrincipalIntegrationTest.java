package com.reused.common.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import com.reused.TestcontainersConfiguration;
import com.reused.auth.token.JwtTokenProvider;
import com.reused.support.ProbeController;
import com.reused.support.ProbeSecurityConfiguration;
import com.reused.user.entity.UserRole;

/**
 * 공개 엔드포인트의 선택 토큰: {@code @AuthUser @Nullable AuthPrincipal}(JSpecify)이 동작하는지 확인한다.
 * Spring 7의 {@code MethodParameter.isOptional()}이 JSpecify {@code @Nullable}을 인식하므로
 * {@link AuthUserArgumentResolver}를 고치지 않아도 된다.
 *
 * <p>JSpecify {@code @Nullable} 주체가 null이 되는 것은 Authorization 헤더를 보내지 않았을 때뿐이다.
 * 헤더를 보냈는데 토큰이 잘못되었으면 {@link JwtAuthenticationFilter}가 컨트롤러 전에 401을 낸다.
 */
@Import({ TestcontainersConfiguration.class, ProbeController.class, ProbeSecurityConfiguration.class })
@SpringBootTest
@AutoConfigureMockMvc
class OptionalPrincipalIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JwtTokenProvider tokenProvider;

	@Test
	@DisplayName("토큰 없이 부르면 주체는 null이고 200이다")
	void anonymousGetsNull() throws Exception {
		mockMvc.perform(get("/test/probe/optional-principal"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.userId").isEmpty());
	}

	@Test
	@DisplayName("유효한 토큰이면 주체가 채워진다")
	void validTokenFillsPrincipal() throws Exception {
		String token = tokenProvider.issueAccessToken(7L, UserRole.USER);

		mockMvc.perform(get("/test/probe/optional-principal").header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.userId").value(7));
	}

	@Test
	@DisplayName("Authorization 헤더의 토큰이 잘못되면 공개 경로여도 401이다(JwtAuthenticationFilter)")
	void invalidTokenIsUnauthenticated() throws Exception {
		mockMvc.perform(get("/test/probe/optional-principal")
						.header("Authorization", "Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.forged"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
				.andExpect(header().string("WWW-Authenticate", "Bearer"));
	}

	@Test
	@DisplayName("@Nullable이 없는 @AuthUser는 공개 경로에서도 로그인이 필요하다")
	void nonNullablePrincipalStillRequiresLogin() throws Exception {
		mockMvc.perform(get("/test/probe/required-principal"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
	}

}
