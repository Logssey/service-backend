package com.reused.user.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.reused.common.security.AuthPrincipal;
import com.reused.common.security.AuthUser;
import com.reused.user.dto.response.MyProfileResponse;
import com.reused.user.dto.response.NicknameAvailabilityResponse;
import com.reused.user.service.UserService;
import com.reused.user.service.UserProfileLifecycleService;
import com.reused.user.dto.request.ProfileUpdateRequest;
import com.reused.user.dto.request.PasswordChangeRequest;
import com.reused.auth.service.RefreshTokenCookieFactory;
import com.reused.auth.service.EmailAuthService;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

	private final UserService userService;
	private final UserProfileLifecycleService lifecycle;
	private final RefreshTokenCookieFactory cookies;
	private final EmailAuthService emailAuthService;

	public UserController(UserService userService, UserProfileLifecycleService lifecycle, RefreshTokenCookieFactory cookies,
			EmailAuthService emailAuthService) {
		this.userService = userService;
		this.lifecycle = lifecycle;
		this.cookies = cookies;
		this.emailAuthService = emailAuthService;
	}

	@PatchMapping("/me")
	public MyProfileResponse update(@AuthUser AuthPrincipal principal, @Valid @RequestBody ProfileUpdateRequest request) {
		return lifecycle.update(principal, request);
	}

	@DeleteMapping("/me")
	public ResponseEntity<Void> withdraw(@AuthUser AuthPrincipal principal) {
		lifecycle.withdraw(principal);
		return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookies.expired().toString()).build();
	}

	/** PATCH /users/me/password — USER. 성공하면 모든 Refresh Token이 폐기되고 새 토큰은 없다. 프론트는 다시 로그인시킨다. */
	@PatchMapping("/me/password")
	public ResponseEntity<Void> changePassword(@AuthUser AuthPrincipal principal, @Valid @RequestBody PasswordChangeRequest request) {
		emailAuthService.changePassword(principal.userId(), request.currentPassword(), request.newPassword());
		return ResponseEntity.noContent().build();
	}

	/** GET /users/me — USER */
	@GetMapping("/me")
	public MyProfileResponse me(@AuthUser AuthPrincipal principal) {
		return userService.getMyProfile(principal.userId());
	}

	/** GET /users/nickname/check?nickname= — 인증 불필요 */
	@GetMapping("/nickname/check")
	public NicknameAvailabilityResponse checkNickname(@RequestParam(required = false) String nickname) {
		return new NicknameAvailabilityResponse(userService.isNicknameAvailable(nickname));
	}

}
