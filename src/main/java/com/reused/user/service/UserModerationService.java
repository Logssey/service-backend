package com.reused.user.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.reused.audit.api.AuditAction;
import com.reused.audit.api.AuditEntry;
import com.reused.audit.api.AuditLogger;
import com.reused.audit.api.AuditTargetType;
import com.reused.auth.token.RefreshTokenStore;
import com.reused.common.error.BusinessException;
import com.reused.common.error.ErrorCode;
import com.reused.common.tx.AfterCommit;
import com.reused.user.config.ModerationProperties;
import com.reused.user.entity.User;
import com.reused.user.entity.UserStatus;
import com.reused.user.entity.UserStatusHistory;
import com.reused.user.repository.UserRepository;
import com.reused.user.repository.UserStatusHistoryRepository;

import jakarta.persistence.EntityManager;

/**
 * 이용정지·해제. 관리자 회원 상태 변경과 신고 처리의 SUSPEND_USER가 이 서비스를 함께 쓴다.
 *
 * <p>상태 변경, 이력, 감사 기록은 한 트랜잭션이다. 감사 기록이 실패하면 변경도 롤백된다(NFR-AUTH-013).
 * 정지는 커밋 뒤에 대상의 Refresh Token을 전부 폐기한다(회원 상태 변경 명세). 이미 발급된 Access Token은
 * 최대 30분 남지만 각 기능의 {@code ActiveUserGuard}가 막는다. 계정 알림 유형이 없어 알림은 보내지 않는다.
 */
@Service
public class UserModerationService {

	private static final Logger log = LoggerFactory.getLogger(UserModerationService.class);

	static final String SOURCE_ADMIN = "ADMIN";
	static final String SOURCE_REPORT = "REPORT";

	/** user_status_histories.reason VARCHAR(500) NOT NULL */
	private static final int REASON_MAX_LENGTH = 500;

	private final UserRepository userRepository;
	private final UserStatusHistoryRepository historyRepository;
	private final AuditLogger auditLogger;
	private final RefreshTokenStore refreshTokenStore;
	private final ModerationProperties properties;
	private final EntityManager entityManager;

	public UserModerationService(UserRepository userRepository, UserStatusHistoryRepository historyRepository,
			AuditLogger auditLogger, RefreshTokenStore refreshTokenStore, ModerationProperties properties,
			EntityManager entityManager) {
		this.userRepository = userRepository;
		this.historyRepository = historyRepository;
		this.auditLogger = auditLogger;
		this.refreshTokenStore = refreshTokenStore;
		this.properties = properties;
		this.entityManager = entityManager;
	}

	/**
	 * 관리자 회원 상태 변경. 이미 정지 중이면 기간만 바꾼다. 정확히 {@code until}로 설정한다.
	 *
	 * @param until 정지 종료 시각. null이면 무기한(회원 상태 변경 명세)
	 * @throws BusinessException INVALID_INPUT 사유 누락·500자 초과, until이 현재 이전.
	 *         FORBIDDEN 본인 대상. NOT_FOUND 대상 없음. CONFLICT 탈퇴 회원
	 */
	@Transactional
	public ModerationResult suspendByAdmin(Long adminId, Long targetUserId, Instant until, String reason) {
		Instant now = Instant.now();
		requireReason(reason);
		if (until != null && !until.isAfter(now)) {
			throw new BusinessException(ErrorCode.INVALID_INPUT, "정지 종료 시각은 현재 시각 이후여야 합니다.");
		}
		User target = lockTarget(adminId, targetUserId);
		return suspend(adminId, target, until, reason, now, SOURCE_ADMIN, null);
	}

	/**
	 * 신고 처리의 SUSPEND_USER. 기본 기간({@code app.moderation.default-suspension})을 적용하되
	 * 이미 더 긴 정지나 무기한 정지를 줄이지 않는다.
	 *
	 * @param reason 신고 처리 결과(resolution)
	 * @throws BusinessException {@link #suspendByAdmin}과 같은 규칙
	 */
	@Transactional
	public ModerationResult suspendForReport(Long adminId, Long targetUserId, String reason, Long reportId) {
		Instant now = Instant.now();
		requireReason(reason);
		User target = lockTarget(adminId, targetUserId);
		return suspend(adminId, target, reportSuspensionEnd(target, now), reason, now, SOURCE_REPORT, reportId);
	}

	/**
	 * 정지 해제. 기간이 지났지만 상태값이 아직 SUSPENDED인 회원도 해제할 수 있다.
	 *
	 * @throws BusinessException CONFLICT 이미 ACTIVE이거나 탈퇴 회원. 나머지는 {@link #suspendByAdmin}과 같다
	 */
	@Transactional
	public ModerationResult activate(Long adminId, Long targetUserId, String reason) {
		Instant now = Instant.now();
		requireReason(reason);
		User target = lockTarget(adminId, targetUserId);
		if (target.getStatus() == UserStatus.ACTIVE) {
			throw new BusinessException(ErrorCode.CONFLICT, "이용정지 상태가 아닌 회원입니다.");
		}

		UserStatus before = target.getStatus();
		Instant previousUntil = target.getSuspendedUntil();
		target.activate(now);
		historyRepository.save(UserStatusHistory.status(target.getId(), before, UserStatus.ACTIVE, reason, adminId));
		auditLogger.record(AuditEntry.success(AuditAction.USER_ACTIVATE, adminId, AuditTargetType.USER,
				target.getId(), detail(before, UserStatus.ACTIVE, null, previousUntil, reason, SOURCE_ADMIN, null)));
		return ModerationResult.of(target);
	}

	/**
	 * 상태나 기간이 실제로 바뀔 때만 이력을 남긴다. 감사 기록과 토큰 폐기는 관리자 조치 자체이므로 항상 한다.
	 */
	private ModerationResult suspend(Long adminId, User target, Instant until, String reason, Instant now,
			String source, Long reportId) {
		UserStatus before = target.getStatus();
		Instant previousUntil = target.getSuspendedUntil();
		if (before != UserStatus.SUSPENDED || !Objects.equals(previousUntil, until)) {
			target.suspend(until, now);
			historyRepository.save(
					UserStatusHistory.status(target.getId(), before, UserStatus.SUSPENDED, reason, adminId));
		}
		auditLogger.record(AuditEntry.success(AuditAction.USER_SUSPEND, adminId, AuditTargetType.USER,
				target.getId(), detail(before, UserStatus.SUSPENDED, until, previousUntil, reason, source, reportId)));

		Long targetId = target.getId();
		AfterCommit.run(() -> revokeRefreshTokens(targetId));
		return ModerationResult.of(target);
	}

	/**
	 * 기간이 지난 정지는 정지 중이 아닌 것으로 보고 새 기간을 준다. 정지 중이면 max(기존, 기본 기간), 무기한은 그대로다.
	 */
	private Instant reportSuspensionEnd(User target, Instant now) {
		Instant defaultEnd = now.plus(properties.defaultSuspension());
		if (!target.isSuspendedAt(now)) {
			return defaultEnd;
		}
		Instant current = target.getSuspendedUntil();
		if (current == null) {
			return null;
		}
		return current.isAfter(defaultEnd) ? current : defaultEnd;
	}

	/**
	 * 본인 확인 → 잠금 조회 → 다시 읽기 → 탈퇴 확인 순서다. 잠금으로 동시 상태 변경·탈퇴와 직렬화한다.
	 *
	 * <p>호출자(신고 처리 등)가 같은 트랜잭션에서 대상을 먼저 읽었으면 잠금 조회는 행을 잠그기만 하고 영속성 컨텍스트의
	 * 옛 값을 그대로 돌려준다. 그 값으로 판단하면 그사이 다른 관리자가 커밋한 정지를 덮어쓴다. 잠금을 잡은 뒤 다시 읽는다.
	 * 잠금 조회가 먼저 플러시하므로 호출자가 바꾼 값은 사라지지 않는다.
	 */
	private User lockTarget(Long adminId, Long targetUserId) {
		if (Objects.equals(adminId, targetUserId)) {
			throw new BusinessException(ErrorCode.FORBIDDEN, "본인의 상태는 변경할 수 없습니다.");
		}
		User target = userRepository.findByIdForUpdate(targetUserId)
				.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "회원을 찾을 수 없습니다."));
		entityManager.refresh(target);
		if (target.isWithdrawn()) {
			throw new BusinessException(ErrorCode.CONFLICT, "탈퇴한 회원입니다.");
		}
		return target;
	}

	/**
	 * 정지는 이미 커밋되었다. 폐기 실패로 커밋된 조치가 500으로 응답되지 않게 삼키고 로그로 남긴다.
	 * 로그인은 DB의 정지 판정이 계속 막는다.
	 */
	private void revokeRefreshTokens(Long userId) {
		try {
			refreshTokenStore.revokeAll(userId);
		}
		catch (RuntimeException e) {
			log.error("정지 회원의 Refresh Token 폐기 실패. 정지는 반영되었다. userId={}", userId, e);
		}
	}

	private static void requireReason(String reason) {
		if (reason == null || reason.isBlank()) {
			throw new BusinessException(ErrorCode.INVALID_INPUT, "사유는 필수입니다.");
		}
		if (reason.length() > REASON_MAX_LENGTH) {
			throw new BusinessException(ErrorCode.INVALID_INPUT, "사유는 500자 이하여야 합니다.");
		}
	}

	/**
	 * 감사 detail. 시각은 ISO-8601 문자열이다. 사유는 관리자 입력이라 남긴다(AuditEntry 규칙).
	 */
	private static Map<String, Object> detail(UserStatus before, UserStatus after, Instant suspendedUntil,
			Instant previousSuspendedUntil, String reason, String source, Long reportId) {
		Map<String, Object> detail = new LinkedHashMap<>();
		detail.put("before", before.name());
		detail.put("after", after.name());
		detail.put("suspendedUntil", suspendedUntil == null ? null : suspendedUntil.toString());
		detail.put("previousSuspendedUntil", previousSuspendedUntil == null ? null : previousSuspendedUntil.toString());
		detail.put("reason", reason);
		if (reportId != null) {
			detail.put("reportId", reportId);
		}
		detail.put("source", source);
		return detail;
	}

	/**
	 * @param suspendedUntil 무기한 정지와 ACTIVE는 null
	 */
	public record ModerationResult(Long userId, UserStatus status, Instant suspendedUntil) {

		static ModerationResult of(User user) {
			return new ModerationResult(user.getId(), user.getStatus(), user.getSuspendedUntil());
		}

	}

}
