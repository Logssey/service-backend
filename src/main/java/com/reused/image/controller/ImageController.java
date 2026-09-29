package com.reused.image.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.reused.common.security.AuthPrincipal;
import com.reused.common.security.AuthUser;
import com.reused.image.dto.ImageUploadResultResponse;
import com.reused.image.dto.ImageUploadUrlRequest;
import com.reused.image.dto.ImageUploadUrlResponse;
import com.reused.image.service.ImageService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/images")
public class ImageController {

	private final ImageService imageService;

	public ImageController(ImageService imageService) {
		this.imageService = imageService;
	}

	@PostMapping("/upload-url")
	@ResponseStatus(HttpStatus.CREATED)
	public ImageUploadUrlResponse uploadUrl(@AuthUser AuthPrincipal principal,
			@Valid @RequestBody ImageUploadUrlRequest request) {
		return imageService.issueUploadUrl(principal, request);
	}

	@PostMapping("/{imageId}/complete")
	public ImageUploadResultResponse complete(@AuthUser AuthPrincipal principal, @PathVariable Long imageId) {
		return imageService.complete(principal, imageId);
	}

	@DeleteMapping("/{imageId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@AuthUser AuthPrincipal principal, @PathVariable Long imageId) {
		imageService.delete(principal, imageId);
	}
}
