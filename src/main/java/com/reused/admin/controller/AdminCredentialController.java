package com.reused.admin.controller;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.reused.admin.dto.response.CredentialStatusResponse;
import com.reused.admin.service.CredentialStatusService;
import com.reused.common.security.AuthPrincipal;
import com.reused.common.security.AuthUser;

/** SecurityConfig and AdminAccessInterceptor enforce current ADMIN status. */
@RestController
@RequestMapping("/api/v1/admin/credentials")
public class AdminCredentialController {

	private final CredentialStatusService credentialStatusService;

	public AdminCredentialController(CredentialStatusService credentialStatusService) {
		this.credentialStatusService = credentialStatusService;
	}

	@GetMapping("/status")
	public ResponseEntity<CredentialStatusResponse> status(@AuthUser AuthPrincipal principal) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
				.body(credentialStatusService.status(principal.userId()));
	}
}
