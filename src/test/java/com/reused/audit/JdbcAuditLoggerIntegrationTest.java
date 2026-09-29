package com.reused.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.reused.TestcontainersConfiguration;
import com.reused.audit.api.AuditAction;
import com.reused.audit.api.AuditEntry;
import com.reused.audit.api.AuditLogger;
import com.reused.audit.api.AuditTargetType;
import com.reused.auth.client.OAuthProviderClient;
import com.reused.auth.mail.AuthMailSender;

/**
 * 감사 기록기의 트랜잭션 경계와 저장 형식. 실제 Postgres에서 INET·JSONB 변환까지 확인한다.
 * 외부 대역 구성은 인증 통합 테스트와 같게 두어 컨텍스트를 함께 쓴다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class JdbcAuditLoggerIntegrationTest {

	private static final long MISSING_USER_ID = 999L;

	@Autowired
	private AuditLogger auditLogger;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@MockitoBean
	private AuthMailSender mailSender;

	@MockitoBean
	private OAuthProviderClient kakaoOAuthClient;

	private Long userId;

	@BeforeEach
	void resetState() {
		jdbcTemplate.execute("TRUNCATE audit_logs, notification_settings, user_status_histories, user_identities, users "
				+ "RESTART IDENTITY CASCADE");
		userId = jdbcTemplate.queryForObject(
				"INSERT INTO users (nickname, terms_agreed_at) VALUES ('감사대상', now()) RETURNING user_id", Long.class);
	}

	@AfterEach
	void clearRequest() {
		RequestContextHolder.resetRequestAttributes();
	}

	// --- 트랜잭션 경계 ---

	@Test
	@DisplayName("record는 호출자 트랜잭션에 참여해 호출자가 롤백되면 함께 사라진다")
	void recordJoinsCallerTransaction() {
		new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			auditLogger.record(entry(userId));
			assertThat(count()).isEqualTo(1); // 같은 트랜잭션 안에서는 보인다
			status.setRollbackOnly();
		});

		assertThat(count()).isZero();
	}

	@Test
	@DisplayName("record는 호출자 트랜잭션이 커밋되면 함께 남는다. 트랜잭션 밖에서 불러도 된다")
	void recordCommitsWithCaller() {
		new TransactionTemplate(transactionManager).executeWithoutResult(status -> auditLogger.record(entry(userId)));
		auditLogger.record(entry(userId));

		assertThat(count()).isEqualTo(2);
	}

	@Test
	@DisplayName("record의 기록 실패는 예외로 전파된다(기록 없는 관리자 변경을 남기지 않는다)")
	void recordPropagatesFailure() {
		assertThatThrownBy(() -> auditLogger.record(entry(MISSING_USER_ID)))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThat(count()).isZero();
	}

	@Test
	@DisplayName("recordSeparately는 별도 트랜잭션이라 호출자가 롤백되어도 남는다")
	void recordSeparatelySurvivesCallerRollback() {
		new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			auditLogger.recordSeparately(entry(userId));
			status.setRollbackOnly();
		});

		assertThat(count()).isEqualTo(1);
	}

	@Test
	@DisplayName("recordSeparately의 기록 실패는 삼키고 호출자 트랜잭션도 깨뜨리지 않는다")
	void recordSeparatelySwallowsFailure() {
		new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			assertThatCode(() -> auditLogger.recordSeparately(entry(MISSING_USER_ID))).doesNotThrowAnyException();
			auditLogger.record(entry(userId));
		});

		assertThat(count()).isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject("SELECT actor_id FROM audit_logs", Long.class)).isEqualTo(userId);
	}

	// --- 저장 형식 ---

	@Test
	@DisplayName("열 값과 detail JSON이 그대로 저장된다")
	void columnsAndDetailAreStored() {
		Map<String, Object> detail = new LinkedHashMap<>();
		detail.put("before", "ACTIVE");
		detail.put("after", "SUSPENDED");
		detail.put("suspendedUntil", null);
		detail.put("reportId", 42L);
		detail.put("changedFields", List.of("title", "isPinned"));

		auditLogger.record(AuditEntry.failure(AuditAction.USER_SUSPEND, userId, AuditTargetType.USER, 7L, detail));

		// 키가 없으면 jsonb_typeof가 SQL NULL이고, 값이 JSON null이면 'null'이다
		Map<String, Object> row = jdbcTemplate.queryForMap("""
				SELECT actor_id, action, target_type, target_id, result, created_at,
				       detail->>'before' AS before, detail->>'after' AS after,
				       jsonb_typeof(detail->'suspendedUntil') AS until_type,
				       (detail->>'reportId')::bigint AS report_id, detail->'changedFields'->>1 AS second_field
				FROM audit_logs""");
		assertThat(row.get("actor_id")).isEqualTo(userId);
		assertThat(row.get("action")).isEqualTo("USER_SUSPEND");
		assertThat(row.get("target_type")).isEqualTo("USER");
		assertThat(row.get("target_id")).isEqualTo(7L);
		assertThat(row.get("result")).isEqualTo("FAILURE");
		assertThat(row.get("created_at")).isNotNull();
		assertThat(row.get("before")).isEqualTo("ACTIVE");
		assertThat(row.get("after")).isEqualTo("SUSPENDED");
		assertThat(row.get("until_type")).isEqualTo("null");
		assertThat(row.get("report_id")).isEqualTo(42L);
		assertThat(row.get("second_field")).isEqualTo("isPinned");
	}

	@Test
	@DisplayName("요청 밖에서 기록하면 IP가 NULL이다. 행위자·대상·detail이 없으면 NULL이고 빈 detail도 NULL이다")
	void nullableColumnsAreNull() {
		// 테스트 프레임워크가 메서드마다 가짜 요청을 묶어 두므로 스케줄러 같은 요청 밖 상황을 직접 만든다.
		RequestContextHolder.resetRequestAttributes();

		auditLogger.record(AuditEntry.success(AuditAction.EXTERNAL_KAKAO, null, null, null, null));
		auditLogger.record(AuditEntry.success(AuditAction.EXTERNAL_KAKAO, null, null, null, Map.of()));

		assertThat(jdbcTemplate.queryForObject(
				"SELECT count(*) FROM audit_logs WHERE actor_id IS NULL AND target_type IS NULL AND target_id IS NULL "
						+ "AND detail IS NULL AND ip_address IS NULL", Long.class)).isEqualTo(2);
	}

	@Test
	@DisplayName("요청 안에서 기록하면 원격 주소가 ip_address(INET)에 저장된다")
	void ipAddressIsStoredFromRequest() {
		bindRequest("203.0.113.7");
		auditLogger.record(entry(userId));
		bindRequest("2001:db8::1");
		auditLogger.record(entry(userId));

		assertThat(jdbcTemplate.queryForList("SELECT host(ip_address) FROM audit_logs ORDER BY audit_log_id", String.class))
				.containsExactly("203.0.113.7", "2001:db8::1");
	}

	@Test
	@DisplayName("IP 리터럴이 아닌 원격 주소는 버리고 기록은 성공한다")
	void malformedRemoteAddressIsDropped() {
		bindRequest("unknown");
		auditLogger.record(entry(userId));
		bindRequest("fe80::1%eth0");
		auditLogger.record(entry(userId));

		assertThat(jdbcTemplate.queryForList("SELECT host(ip_address) FROM audit_logs ORDER BY audit_log_id", String.class))
				.containsExactly(null, "fe80::1");
	}

	@Test
	@DisplayName("targetId만 있고 targetType이 없는 기록은 만들 수 없다")
	void targetIdRequiresTargetType() {
		assertThatThrownBy(() -> AuditEntry.success(AuditAction.REPORT_HANDLE, userId, null, 1L, null))
				.isInstanceOf(IllegalArgumentException.class);
	}

	private AuditEntry entry(Long actorId) {
		return AuditEntry.success(AuditAction.AUTH_LOGIN, actorId, AuditTargetType.USER, actorId,
				Map.of("provider", "LOCAL"));
	}

	private long count() {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs", Long.class);
	}

	private static void bindRequest(String remoteAddr) {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setRemoteAddr(remoteAddr);
		RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
	}

}
