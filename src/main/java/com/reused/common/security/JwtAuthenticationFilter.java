package com.reused.common.security;

import java.io.IOException;
import java.util.List;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.reused.auth.token.InvalidTokenException;
import com.reused.auth.token.JwtTokenProvider;
import com.reused.auth.token.JwtTokenProvider.AccessTokenClaims;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Authorization 헤더의 Access Token을 검증해 SecurityContext를 채운다.
 *
 * <p>검증은 서명·만료·용도까지이고 DB를 조회하지 않는다(ADR-005 "검증: 서명 검증만").
 * 그래서 이용정지가 최대 30분 늦게 반영되는데, 이는 ADR이 감수하기로 한 결과다.
 * 정지 상태에서 막아야 하는 행위는 해당 기능을 수행하는 쪽에서 다시 확인해야 한다.
 *
 * <p>토큰이 없거나 잘못된 경우 여기서 401을 내지 않고 인증 없이 통과시킨다.
 * 보호 여부는 SecurityConfig의 인가 규칙이 판단한다. 공개 엔드포인트가 로그인 시에만
 * 응답을 보강하는 경우(게시글 상세의 isWished 등)를 지원하기 위함이다.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

	private static final String HEADER = "Authorization";
	private static final String PREFIX = "Bearer ";

	private final JwtTokenProvider tokenProvider;

	public JwtAuthenticationFilter(JwtTokenProvider tokenProvider) {
		this.tokenProvider = tokenProvider;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String token = resolveToken(request);
		if (token != null) {
			authenticate(token);
		}
		chain.doFilter(request, response);
	}

	private void authenticate(String token) {
		try {
			AccessTokenClaims claims = tokenProvider.parseAccessToken(token);
			AuthPrincipal principal = new AuthPrincipal(claims.userId(), claims.role());
			var authentication = new UsernamePasswordAuthenticationToken(
					principal, null,
					List.of(new SimpleGrantedAuthority("ROLE_" + claims.role().name())));
			SecurityContextHolder.getContext().setAuthentication(authentication);
		}
		catch (InvalidTokenException e) {
			SecurityContextHolder.clearContext();
		}
	}

	private String resolveToken(HttpServletRequest request) {
		String header = request.getHeader(HEADER);
		if (header == null || !header.startsWith(PREFIX)) {
			return null;
		}
		String token = header.substring(PREFIX.length()).trim();
		return token.isEmpty() ? null : token;
	}

}
