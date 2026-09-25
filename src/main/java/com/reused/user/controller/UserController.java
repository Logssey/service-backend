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
import com.reused.auth.service.RefreshTokenCookieFactory;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

	private final UserService userService;
	private final UserProfileLifecycleService lifecycle;
	private final RefreshTokenCookieFactory cookies;

	public UserController(UserService userService, UserProfileLifecycleService lifecycle, RefreshTokenCookieFactory cookies) {
		this.userService = userService;
		this.lifecycle = lifecycle;
		this.cookies = cookies;
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
