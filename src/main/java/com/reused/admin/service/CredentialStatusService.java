package com.reused.admin.service;

import java.util.List;
import java.util.Map;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.reused.admin.dto.response.CredentialStatusResponse;
import com.reused.admin.dto.response.CredentialStatusResponse.Credential;
import com.reused.audit.api.AuditAction;
import com.reused.audit.api.AuditEntry;
import com.reused.audit.api.AuditLogger;
import com.reused.chatbot.llm.LlmClient;
import com.reused.chatbot.service.ChatbotFeatureService;

/**
 * Reports whether required integration settings are present, never their values.
 * A configured setting does not prove that the external service is reachable.
 */
@Service
public class CredentialStatusService {

	private static final String APPLICATION_CONFIGURATION = "APPLICATION_CONFIGURATION";
	private static final String PROVIDER_CHAIN = "DEFAULT_PROVIDER_CHAIN";

	private final Environment environment;
	private final ChatbotFeatureService chatbotFeatureService;
	private final LlmClient llmClient;
	private final AuditLogger auditLogger;

	public CredentialStatusService(Environment environment, ChatbotFeatureService chatbotFeatureService,
			LlmClient llmClient, AuditLogger auditLogger) {
		this.environment = environment;
		this.chatbotFeatureService = chatbotFeatureService;
		this.llmClient = llmClient;
		this.auditLogger = auditLogger;
	}

	public CredentialStatusResponse status(long adminId) {
		boolean imageStorageConfigured = present("app.image.s3.bucket");
		// 공급자마다 키 설정 이름이 달라서, 설정 이름 대신 활성 어댑터에 묻는다.
		boolean llmConfigured = llmClient.isConfigured();
		boolean llmEnabled = llmConfigured && chatbotFeatureService.status().freeInputEnabled();
		boolean kakaoStub = environment.getProperty("app.kakao.stub", Boolean.class, false);
		CredentialStatusResponse response = new CredentialStatusResponse(List.of(
				credential("JWT", present("app.auth.secret"), true, APPLICATION_CONFIGURATION),
				credential("DATABASE", present("spring.datasource.url")
						&& present("spring.datasource.username"), true, APPLICATION_CONFIGURATION),
				credential("REDIS", present("spring.data.redis.host", "localhost"), true,
						APPLICATION_CONFIGURATION),
				credential("SMTP", present("spring.mail.host"), true, APPLICATION_CONFIGURATION),
				credential("KAKAO_OAUTH", !kakaoStub && present("app.kakao.client-id"), !kakaoStub,
						APPLICATION_CONFIGURATION),
				credential("IMAGE_STORAGE", imageStorageConfigured, imageStorageConfigured, PROVIDER_CHAIN),
				credential("LLM", llmConfigured, llmEnabled, APPLICATION_CONFIGURATION)));
		// This sensitive read is auditable, but the snapshot and setting names never enter the log.
		auditLogger.record(AuditEntry.success(AuditAction.CREDENTIAL_STATUS_VIEW, adminId, null, null, Map.of()));
		return response;
	}

	private static Credential credential(String service, boolean configured, boolean enabled, String source) {
		return new Credential(service, configured, enabled, source);
	}

	private boolean present(String property) {
		return StringUtils.hasText(environment.getProperty(property));
	}

	private boolean present(String property, String defaultValue) {
		return StringUtils.hasText(environment.getProperty(property, defaultValue));
	}
}
