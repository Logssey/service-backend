package com.reused.user.service;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.reused.audit.api.AuditAction;
import com.reused.audit.api.AuditEntry;
import com.reused.audit.api.AuditLogger;
import com.reused.audit.api.AuditTargetType;
import com.reused.user.entity.UserStatus;
import com.reused.user.entity.UserStatusHistory;
import com.reused.user.repository.UserStatusHistoryRepository;

/**
 * 기간이 끝난 이용정지를 ACTIVE로 되돌린다. {@code isSuspendedAt(now)}로 판정하는 곳(ActiveUserGuard, 로그인,
 * AdminAccessInterceptor)은 실행 간격만큼 늦어도 정확하다. 그러나 DB 상태값으로 판정하는 곳({@code User.isSuspended()}를 쓰는
 * ActorGuard, TradeUserGuard, 게시글 서비스)과 관리자 목록 필터·대시보드 집계·내 정보의 status는 이 작업이 돌아야 풀린다.
 *
 * <p>예약 실행은 ModerationSchedulingConfig가 한다(app.moderation.expiry-job-enabled).
 */
@Component
public class SuspensionExpiryJob {

	private static final Logger log = LoggerFactory.getLogger(SuspensionExpiryJob.class);

	static final String EXPIRY_REASON = "정지 기간 만료";
	static final String SOURCE_EXPIRY = "EXPIRY";

	/**
	 * 조건부 UPDATE라 여러 인스턴스가 동시에 돌아도 행마다 한 번만 갱신된다. 늦게 온 쪽은 행 잠금을 기다린 뒤
	 * 조건을 다시 평가해 건너뛴다. 분산 락이 필요 없다.
	 */
	private static final String RELEASE_SQL = """
			UPDATE users SET status = 'ACTIVE', suspended_until = NULL, updated_at = now()
			WHERE status = 'SUSPENDED' AND suspended_until <= now()
			RETURNING user_id""";

	private final JdbcTemplate jdbcTemplate;
	private final UserStatusHistoryRepository historyRepository;
	private final AuditLogger auditLogger;

	public SuspensionExpiryJob(JdbcTemplate jdbcTemplate, UserStatusHistoryRepository historyRepository,
			AuditLogger auditLogger) {
		this.jdbcTemplate = jdbcTemplate;
		this.historyRepository = historyRepository;
		this.auditLogger = auditLogger;
	}

	/**
	 * 해제, 이력, 감사 기록이 한 트랜잭션이다. 기록이 실패하면 해제도 롤백되고 다음 실행에서 다시 시도한다.
	 * 무기한 정지(suspended_until NULL)는 대상이 아니다.
	 *
	 * @return 해제한 회원 수
	 */
	@Transactional
	public int releaseExpired() {
		List<Long> released = jdbcTemplate.queryForList(RELEASE_SQL, Long.class);
		for (Long userId : released) {
			historyRepository.save(
					UserStatusHistory.status(userId, UserStatus.SUSPENDED, UserStatus.ACTIVE, EXPIRY_REASON, null));
			auditLogger.record(AuditEntry.success(AuditAction.USER_ACTIVATE, null, AuditTargetType.USER, userId,
					Map.of("source", SOURCE_EXPIRY)));
		}
		if (!released.isEmpty()) {
			log.info("기간이 끝난 이용정지 {}건을 해제했습니다.", released.size());
		}
		return released.size();
	}

}
