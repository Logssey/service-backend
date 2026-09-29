package com.reused.chatbot.service;

import java.util.List;
import java.util.Map;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.reused.audit.api.AuditAction;
import com.reused.audit.api.AuditEntry;
import com.reused.audit.api.AuditLogger;
import com.reused.chatbot.config.ChatbotProperties;
import com.reused.chatbot.dto.response.ChatbotFeatureStatusResponse;
import com.reused.common.error.BusinessException;
import com.reused.common.error.ErrorCode;

/** Shared DB state makes an admin shutdown visible to every API instance without a restart. */
@Service
public class ChatbotFeatureService {

	private static final String SELECT = "SELECT enabled FROM service_feature_flags WHERE feature_key = 'CHATBOT'";
	private static final String UNAVAILABLE = "현재 챗봇 설정을 확인할 수 없습니다.";

	private final JdbcTemplate jdbc;
	private final ChatbotProperties properties;
	private final AuditLogger auditLogger;

	public ChatbotFeatureService(JdbcTemplate jdbc, ChatbotProperties properties, AuditLogger auditLogger) {
		this.jdbc = jdbc;
		this.properties = properties;
		this.auditLogger = auditLogger;
	}

	public boolean isEnabled() {
		return properties.enabled() && readAdminEnabled(false);
	}

	public ChatbotFeatureStatusResponse status() {
		return response(readAdminEnabled(false));
	}

	@Transactional
	public ChatbotFeatureStatusResponse update(long adminId, boolean enabled) {
		if (enabled && !properties.enabled()) {
			throw new BusinessException(ErrorCode.CONFLICT, "환경 설정에서 챗봇이 비활성화되어 있습니다.");
		}
		boolean previous = readAdminEnabled(true);
		if (previous != enabled) {
			try {
				if (jdbc.update("UPDATE service_feature_flags SET enabled = ?, updated_at = now() WHERE feature_key = 'CHATBOT'",
						enabled) != 1) {
					throw unavailable();
				}
			}
			catch (DataAccessException ex) {
				throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, UNAVAILABLE, ex);
			}
			auditLogger.record(AuditEntry.success(AuditAction.CHATBOT_STATUS_UPDATE, adminId, null, null,
					Map.of("before", previous, "after", enabled)));
		}
		return response(enabled);
	}

	private ChatbotFeatureStatusResponse response(boolean adminEnabled) {
		boolean enabled = properties.enabled() && adminEnabled;
		return new ChatbotFeatureStatusResponse(enabled, adminEnabled, properties.enabled(),
				enabled && properties.freeInputEnabled());
	}

	private boolean readAdminEnabled(boolean lock) {
		try {
			List<Boolean> rows = jdbc.query(SELECT + (lock ? " FOR UPDATE" : ""),
					(rs, rowNum) -> rs.getBoolean(1));
			if (rows.size() != 1) {
				throw unavailable();
			}
			return rows.getFirst();
		}
		catch (DataAccessException ex) {
			throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, UNAVAILABLE, ex);
		}
	}

	private static BusinessException unavailable() {
		return new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, UNAVAILABLE);
	}
}
