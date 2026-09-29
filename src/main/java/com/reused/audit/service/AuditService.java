package com.reused.audit.service;

import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class AuditService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public AuditService(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(Long actorId, String action, String targetType, Long targetId, Map<String, ?> detail) {
        jdbc.update("""
                INSERT INTO audit_logs (actor_id, action, target_type, target_id, result, detail)
                VALUES (?, ?, ?, ?, 'SUCCESS', CAST(? AS jsonb))
                """, actorId, action, targetType, targetId, mapper.writeValueAsString(detail));
    }
}
