package com.reused.user.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.reused.common.security.AuthPrincipal;
import com.reused.common.security.AuthUser;
import com.reused.user.dto.response.MyProfileResponse;
import com.reused.user.dto.response.NicknameAvailabilityResponse;
import com.reused.user.service.UserService;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

	private final UserService userService;

	public UserController(UserService userService) {
		this.userService = userService;
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
