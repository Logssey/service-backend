package com.reused.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AdminDeleteRequest(@NotBlank @Size(max = 500) String reason) {
}
