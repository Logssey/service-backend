package com.reused.admin.dto.response;

import java.util.List;

/** Only non-secret configuration metadata is returned to administrators. */
public record CredentialStatusResponse(List<Credential> credentials) {

	public record Credential(String service, boolean configured, boolean enabled, String source) {
	}
}
