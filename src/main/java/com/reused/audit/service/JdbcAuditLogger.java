package com.reused.audit.service;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import tools.jackson.databind.ObjectMapper;

import com.reused.audit.api.AuditEntry;
import com.reused.audit.api.AuditLogger;

/**
 * audit_logs에 JDBC로 INSERT한다. INET·JSONB를 Hibernate로 매핑하지 않으려고 JPA를 쓰지 않는다.
 * JdbcTemplate은 JpaTransactionManager가 연 트랜잭션의 커넥션을 그대로 쓰므로 호출자 트랜잭션에 참여할 수 있다.
 *
 * <p>IP는 현재 요청의 {@code getRemoteAddr()}다. 요청 밖(스케줄러)에서는 null이다.
 * 프록시 뒤에 배포하면 {@code server.forward-headers-strategy} 설정이 있어야 실제 클라이언트 주소가 된다.
 */
@Component
public class JdbcAuditLogger implements AuditLogger {

	private static final Logger log = LoggerFactory.getLogger(JdbcAuditLogger.class);

	private static final String INSERT_SQL = """
			INSERT INTO audit_logs (actor_id, action, target_type, target_id, result, ip_address, detail, created_at)
			VALUES (?, ?, ?, ?, ?, CAST(? AS inet), CAST(? AS jsonb), ?)""";

	private static final Pattern IPV4 = Pattern.compile(
			"((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)");
	private static final Pattern IPV6 = Pattern.compile("[0-9a-fA-F:.]*:[0-9a-fA-F:.]*");

	private final JdbcTemplate jdbcTemplate;
	private final ObjectMapper objectMapper;
	private final TransactionTemplate separateTransaction;

	public JdbcAuditLogger(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
			PlatformTransactionManager transactionManager) {
		this.jdbcTemplate = jdbcTemplate;
		this.objectMapper = objectMapper;
		this.separateTransaction = new TransactionTemplate(transactionManager);
		this.separateTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	@Override
	@Transactional
	public void record(AuditEntry entry) {
		insert(entry);
	}

	/**
	 * {@code @Transactional(REQUIRES_NEW)} 대신 TransactionTemplate을 쓴다. 트랜잭션 시작·커밋 단계의 실패
	 * (커넥션 고갈 등)까지 이 메서드 안에서 잡아 삼키기 위해서다. 어노테이션이면 그 예외는 프록시 밖으로 나간다.
	 */
	@Override
	public void recordSeparately(AuditEntry entry) {
		try {
			separateTransaction.executeWithoutResult(status -> insert(entry));
		}
		catch (RuntimeException e) {
			// 식별자만 남긴다. detail에는 사유 같은 자유 입력이 있을 수 있다.
			log.warn("감사 로그 기록 실패. action={}, result={}, actorId={}, targetType={}, targetId={}",
					entry.action(), entry.result(), entry.actorId(), entry.targetType(), entry.targetId(), e);
		}
	}

	private void insert(AuditEntry entry) {
		String detail = entry.detail().isEmpty() ? null : objectMapper.writeValueAsString(entry.detail());
		String ipAddress = currentIpAddress();
		OffsetDateTime createdAt = OffsetDateTime.ofInstant(Instant.now(), ZoneOffset.UTC);

		jdbcTemplate.update(INSERT_SQL, ps -> {
			setLong(ps, 1, entry.actorId());
			ps.setString(2, entry.action().name());
			setString(ps, 3, entry.targetType() == null ? null : entry.targetType().name());
			setLong(ps, 4, entry.targetId());
			ps.setString(5, entry.result().name());
			setString(ps, 6, ipAddress);
			setString(ps, 7, detail);
			ps.setObject(8, createdAt, Types.TIMESTAMP_WITH_TIMEZONE);
		});
	}

	/**
	 * INET으로 변환할 수 없는 값이 오면 기록 자체가 실패해 관리자 조치까지 롤백된다. IP 리터럴 형태가 아니면 버린다.
	 */
	private static String currentIpAddress() {
		if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
			return null;
		}
		String address = attributes.getRequest().getRemoteAddr();
		if (address == null) {
			return null;
		}
		// IPv6 zone id(fe80::1%eth0)는 inet이 받지 않는다.
		int zone = address.indexOf('%');
		if (zone >= 0) {
			address = address.substring(0, zone);
		}
		if (IPV4.matcher(address).matches() || IPV6.matcher(address).matches()) {
			return address;
		}
		return null;
	}

	private static void setLong(PreparedStatement ps, int index, Long value) throws SQLException {
		if (value == null) {
			ps.setNull(index, Types.BIGINT);
		}
		else {
			ps.setLong(index, value);
		}
	}

	private static void setString(PreparedStatement ps, int index, String value) throws SQLException {
		if (value == null) {
			ps.setNull(index, Types.VARCHAR);
		}
		else {
			ps.setString(index, value);
		}
	}

}
