package com.reused.auth.dto.request;

import jakarta.validation.constraints.NotBlank;

public record EmailVerificationConfirmRequest(@NotBlank String code) {
}
