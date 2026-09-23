package com.reused.auth.controller;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.reused.auth.dto.request.KakaoLoginRequest;
import com.reused.auth.dto.request.SignupRequest;
import com.reused.auth.dto.response.AccessTokenResponse;
import com.reused.auth.dto.response.AuthTokenResponse;
import com.reused.auth.dto.response.KakaoLoginResponse;
import com.reused.auth.service.AuthService;
import com.reused.auth.service.RefreshTokenCookieFactory;
import com.reused.auth.token.InvalidTokenException;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

	private final AuthService authService;
	private final RefreshTokenCookieFactory cookieFactory;

	public AuthController(AuthService authService, RefreshTokenCookieFactory cookieFactory) {
		this.authService = authService;
		this.cookieFactory = cookieFactory;
	}

	@PostMapping("/kakao")
	public ResponseEntity<KakaoLoginResponse> kakaoLogin(@Valid @RequestBody KakaoLoginRequest request) {
		AuthService.LoginResult result = authService.login(request);
		return withRefreshCookie(ResponseEntity.ok(), result.refreshToken()).body(result.response());
	}

	@PostMapping("/signup")
	public ResponseEntity<AuthTokenResponse> signup(@Valid @RequestBody SignupRequest request) {
		AuthService.SignupResult result = authService.signup(request);
		return withRefreshCookie(ResponseEntity.status(HttpStatus.CREATED), result.refreshToken())
				.body(result.response());
	}

	@PostMapping("/refresh")
	public ResponseEntity<AccessTokenResponse> refresh(
			@CookieValue(name = "${app.auth.cookie.name}", required = false) String refreshToken) {
		if (refreshToken == null) {
			throw new InvalidTokenException("Refresh Token 쿠키가 없습니다.");
		}
		AuthService.RefreshResult result = authService.refresh(refreshToken);
		return withRefreshCookie(ResponseEntity.ok(), result.refreshToken())
				.body(new AccessTokenResponse(result.accessToken()));
	}

	@PostMapping("/logout")
	public ResponseEntity<Void> logout(
			@CookieValue(name = "${app.auth.cookie.name}", required = false) String refreshToken) {
		authService.logout(refreshToken);
		return ResponseEntity.noContent()
				.header(HttpHeaders.SET_COOKIE, cookieFactory.expired().toString())
				.build();
	}

	private ResponseEntity.BodyBuilder withRefreshCookie(ResponseEntity.BodyBuilder builder, String refreshToken) {
		if (refreshToken == null) {
			return builder;
		}
		ResponseCookie cookie = cookieFactory.create(refreshToken);
		return builder.header(HttpHeaders.SET_COOKIE, cookie.toString());
	}

}
