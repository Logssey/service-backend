package com.reused.user.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.reused.user.dto.response.NicknameAvailabilityResponse;
import com.reused.user.service.UserService;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

	private final UserService userService;

	public UserController(UserService userService) {
		this.userService = userService;
	}

	/** GET /users/nickname/check?nickname= — 인증 불필요 */
	@GetMapping("/nickname/check")
	public NicknameAvailabilityResponse checkNickname(@RequestParam(required = false) String nickname) {
		return new NicknameAvailabilityResponse(userService.isNicknameAvailable(nickname));
	}

}
