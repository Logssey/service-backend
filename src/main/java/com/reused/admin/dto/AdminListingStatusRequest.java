package com.reused.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AdminListingStatusRequest(
		@NotNull @Pattern(regexp = "HIDDEN|RESTORE") String status,
		@NotBlank @Size(max = 500) String reason) {
}
