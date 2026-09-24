package com.reused.common.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

	private final JwtAuthenticationFilter jwtAuthenticationFilter;
	private final RestAuthenticationEntryPoint authenticationEntryPoint;
	private final RestAccessDeniedHandler accessDeniedHandler;

	public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
			RestAuthenticationEntryPoint authenticationEntryPoint,
			RestAccessDeniedHandler accessDeniedHandler) {
		this.jwtAuthenticationFilter = jwtAuthenticationFilter;
		this.authenticationEntryPoint = authenticationEntryPoint;
		this.accessDeniedHandler = accessDeniedHandler;
	}

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		return http
				// 토큰 기반이라 서버 세션을 두지 않는다(ADR-005는 서버 세션 방식을 기각했다).
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				// Refresh Token 쿠키는 SameSite 속성으로 교차 사이트 전송을 막는다.
				.csrf(AbstractHttpConfigurer::disable)
				.formLogin(AbstractHttpConfigurer::disable)
				.httpBasic(AbstractHttpConfigurer::disable)
				.logout(AbstractHttpConfigurer::disable)
				.authorizeHttpRequests(auth -> auth
						.requestMatchers(HttpMethod.GET, "/api/v1/categories").permitAll()
						.requestMatchers(HttpMethod.GET, "/api/v1/listings", "/api/v1/listings/{listingId}").permitAll()
						.requestMatchers(HttpMethod.GET, "/api/v1/users/nickname/check").permitAll()
						// 인증 진입점. 로그아웃과 이메일 소유 확인만 USER 권한이 필요하다.
						.requestMatchers(HttpMethod.POST, "/api/v1/auth/kakao").permitAll()
						.requestMatchers(HttpMethod.POST, "/api/v1/auth/signup").permitAll()
						.requestMatchers(HttpMethod.POST, "/api/v1/auth/refresh").permitAll()
						.requestMatchers(HttpMethod.POST, "/api/v1/auth/email/signup").permitAll()
						.requestMatchers(HttpMethod.POST, "/api/v1/auth/email/login").permitAll()
						.requestMatchers(HttpMethod.POST, "/api/v1/auth/password/reset").permitAll()
						.requestMatchers(HttpMethod.POST, "/api/v1/auth/password/reset/confirm").permitAll()
						.anyRequest().authenticated())
				.exceptionHandling(handler -> handler
						.authenticationEntryPoint(authenticationEntryPoint)
						.accessDeniedHandler(accessDeniedHandler))
				.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
				.build();
	}

}
