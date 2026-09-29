package com.reused.auth.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.reused.auth.dto.request.EmailLoginRequest;
import com.reused.auth.dto.request.EmailSignupRequest;
import com.reused.auth.dto.request.EmailVerificationConfirmRequest;
import com.reused.auth.dto.request.PasswordResetConfirmRequest;
import com.reused.auth.dto.request.PasswordResetRequest;
import com.reused.auth.dto.response.AuthTokenResponse;
import com.reused.auth.service.EmailAuthService;
import com.reused.auth.service.RefreshTokenCookieFactory;
import com.reused.common.security.AuthPrincipal;
import com.reused.common.security.AuthUser;

import jakarta.validation.Valid;

/**
 * 이메일 계정 인증 엔드포인트(05-api/endpoints/auth).
 */
@RestController
@RequestMapping("/api/v1/auth")
public class EmailAuthController {

	private final EmailAuthService emailAuthService;
	private final RefreshTokenCookieFactory cookieFactory;

	public EmailAuthController(EmailAuthService emailAuthService, RefreshTokenCookieFactory cookieFactory) {
		this.emailAuthService = emailAuthService;
		this.cookieFactory = cookieFactory;
	}

	@PostMapping("/email/signup")
	public ResponseEntity<AuthTokenResponse> signup(@Valid @RequestBody EmailSignupRequest request) {
		EmailAuthService.AuthResult result = emailAuthService.signup(request);
		return RefreshCookies.attach(ResponseEntity.status(HttpStatus.CREATED), cookieFactory, result.refreshToken())
				.body(result.response());
	}

	@PostMapping("/email/login")
	public ResponseEntity<AuthTokenResponse> login(@Valid @RequestBody EmailLoginRequest request) {
		EmailAuthService.AuthResult result = emailAuthService.login(request);
		return RefreshCookies.attach(ResponseEntity.ok(), cookieFactory, result.refreshToken())
				.body(result.response());
	}

	@PostMapping("/email/verification")
	public ResponseEntity<Void> resendVerification(@AuthUser AuthPrincipal principal) {
		emailAuthService.resendVerification(principal.userId());
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/email/verification/confirm")
	public ResponseEntity<Void> confirmVerification(@AuthUser AuthPrincipal principal,
			@Valid @RequestBody EmailVerificationConfirmRequest request) {
		emailAuthService.confirmVerification(principal.userId(), request.code());
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/password/reset")
	public ResponseEntity<Void> requestPasswordReset(@Valid @RequestBody PasswordResetRequest request) {
		emailAuthService.requestPasswordReset(request.email());
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/password/reset/confirm")
	public ResponseEntity<Void> confirmPasswordReset(@Valid @RequestBody PasswordResetConfirmRequest request) {
		emailAuthService.confirmPasswordReset(request);
		return ResponseEntity.noContent().build();
	}

}
