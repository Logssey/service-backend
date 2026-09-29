package com.reused.image.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ImageUploadUrlRequest(
		@NotBlank String purpose,
		@NotBlank @Size(max = 255) String fileName,
		@NotBlank String contentType,
		@NotNull @Min(1) @Max(10485760) Long fileSize) {
}
