package com.reused.common.security;

import java.io.IOException;
import java.util.List;

import org.springframework.security.authentication.BadCredentialsException;
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
 * <p>토큰이 없으면 공개 조회는 익명으로 처리한다. Authorization을 제공한 요청은 공개
 * 엔드포인트라도 토큰 오류를 401로 반환하여 만료된 로그인을 익명 요청으로 바꾸지 않는다.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

	private static final String HEADER = "Authorization";
	private static final String PREFIX = "Bearer ";

	private final JwtTokenProvider tokenProvider;
	private final RestAuthenticationEntryPoint authenticationEntryPoint;

	public JwtAuthenticationFilter(JwtTokenProvider tokenProvider, RestAuthenticationEntryPoint authenticationEntryPoint) {
		this.tokenProvider = tokenProvider;
		this.authenticationEntryPoint = authenticationEntryPoint;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		if (request.getHeader(HEADER) != null) {
			try {
				String token = resolveToken(request);
				if (token == null) throw new InvalidTokenException("Bearer 토큰이 필요합니다.");
				authenticate(token);
			}
			catch (InvalidTokenException | IllegalArgumentException ex) {
				SecurityContextHolder.clearContext();
				response.setHeader("WWW-Authenticate", "Bearer");
				authenticationEntryPoint.commence(request, response, new BadCredentialsException("유효하지 않은 토큰입니다."));
				return;
			}
		}
		chain.doFilter(request, response);
	}

	private void authenticate(String token) {
		AccessTokenClaims claims = tokenProvider.parseAccessToken(token);
		AuthPrincipal principal = new AuthPrincipal(claims.userId(), claims.role());
		var authentication = new UsernamePasswordAuthenticationToken(
				principal, null,
				List.of(new SimpleGrantedAuthority("ROLE_" + claims.role().name())));
		SecurityContextHolder.getContext().setAuthentication(authentication);
	}

	private String resolveToken(HttpServletRequest request) {
		String header = request.getHeader(HEADER);
		if (header == null || !header.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
			return null;
		}
		String token = header.substring(PREFIX.length()).trim();
		return token.isEmpty() ? null : token;
	}

}
