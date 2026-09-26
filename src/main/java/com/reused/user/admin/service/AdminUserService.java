package com.reused.user.admin.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

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
import com.reused.user.admin.dto.request.AdminUserStatusChange;
import com.reused.user.admin.dto.response.UserRoleUpdateResponse;
import com.reused.user.admin.dto.response.UserStatusUpdateResponse;
import com.reused.user.entity.User;
import com.reused.user.entity.UserRole;
import com.reused.user.entity.UserStatusHistory;
import com.reused.user.repository.UserRepository;
import com.reused.user.repository.UserStatusHistoryRepository;
import com.reused.user.service.UserModerationService;
import com.reused.user.service.UserModerationService.ModerationResult;

import jakarta.persistence.EntityManager;

/**
 * 관리자 회원 역할·상태 변경. 변경, 이력, 감사 기록은 한 트랜잭션이다(NFR-AUTH-013).
 *
 * <p>요청자와 대상을 id 오름차순으로 잠근 뒤 요청자가 아직 활성 관리자인지 다시 확인한다. {@code AdminAccessInterceptor}는
 * 요청 시작 시점에 한 번 확인할 뿐이라, 두 관리자가 동시에 서로의 역할을 회수하거나 서로를 정지하면 활성 관리자가 0명이 될 수
 * 있다. 잠금 순서를 고정해 교착을 피하고, 늦게 온 쪽은 먼저 커밋된 변경을 보고 403이 된다. 그 사이 요청자가 탈퇴했으면
 * 인터셉터와 같이 401이다.
 *
 * <p>이용정지·해제 자체는 신고 처리와 공유하는 {@link UserModerationService}가 한다.
 */
@Service
public class AdminUserService {

	private static final Logger log = LoggerFactory.getLogger(AdminUserService.class);

	private final UserRepository userRepository;
	private final UserStatusHistoryRepository historyRepository;
	private final AuditLogger auditLogger;
	private final RefreshTokenStore refreshTokenStore;
	private final UserModerationService moderationService;
	private final EntityManager entityManager;

	public AdminUserService(UserRepository userRepository, UserStatusHistoryRepository historyRepository,
			AuditLogger auditLogger, RefreshTokenStore refreshTokenStore, UserModerationService moderationService,
			EntityManager entityManager) {
		this.userRepository = userRepository;
		this.historyRepository = historyRepository;
		this.auditLogger = auditLogger;
		this.refreshTokenStore = refreshTokenStore;
		this.moderationService = moderationService;
		this.entityManager = entityManager;
	}

	/**
	 * 역할 부여·회수. 회수하면 커밋 뒤 대상의 Refresh Token을 전부 폐기한다. 남은 Access Token으로 관리자 API를 부르는 것은
	 * {@code AdminAccessInterceptor}가 DB 역할로 막는다.
	 *
	 * @param reason 공백이 아닌 500자 이하(요청 검증을 거친 값). 앞뒤 공백을 떼고 저장한다
	 * @throws BusinessException UNAUTHENTICATED 요청자가 없거나 탈퇴. FORBIDDEN 본인 대상 또는 요청자의 역할 회수·정지.
	 *         NOT_FOUND 대상 없음. CONFLICT 탈퇴 회원, 이미 같은 역할, 이용정지 중인 회원에게 ADMIN 부여
	 */
	@Transactional
	public UserRoleUpdateResponse changeRole(Long adminId, Long targetUserId, UserRole role, String reason) {
		Objects.requireNonNull(role, "role");
		if (Objects.equals(adminId, targetUserId)) {
			throw new BusinessException(ErrorCode.FORBIDDEN, "본인의 역할은 변경할 수 없습니다.");
		}
		Instant now = Instant.now();
		String trimmedReason = reason.strip();
		User target = lockRequesterAndTarget(adminId, targetUserId, now);
		if (target.isWithdrawn()) {
			throw new BusinessException(ErrorCode.CONFLICT, "탈퇴한 회원입니다.");
		}
		UserRole before = target.getRole();
		if (before == role) {
			throw new BusinessException(ErrorCode.CONFLICT, "이미 같은 역할인 회원입니다.");
		}
		if (role == UserRole.ADMIN && target.isSuspendedAt(now)) {
			throw new BusinessException(ErrorCode.CONFLICT, "이용정지 중인 회원에게는 관리자 역할을 부여할 수 없습니다.");
		}

		target.changeRole(role, now);
		historyRepository.save(UserStatusHistory.role(target.getId(), before, role, trimmedReason, adminId));
		AuditAction action = role == UserRole.ADMIN ? AuditAction.USER_ROLE_GRANT : AuditAction.USER_ROLE_REVOKE;
		auditLogger.record(AuditEntry.success(action, adminId, AuditTargetType.USER, target.getId(),
				roleDetail(before, role, trimmedReason)));

		if (role == UserRole.USER) {
			Long targetId = target.getId();
			AfterCommit.run(() -> revokeRefreshTokens(targetId));
		}
		return new UserRoleUpdateResponse(target.getId(), target.getRole());
	}

	/**
	 * 이용정지(기간 변경 포함)와 정지 해제. 규칙과 기록은 {@link UserModerationService}를 따른다.
	 *
	 * @param suspendedUntil SUSPENDED일 때만. null이면 무기한
	 * @param reason 공백이 아닌 500자 이하(요청 검증을 거친 값). 앞뒤 공백을 떼고 저장한다
	 * @throws BusinessException INVALID_INPUT ACTIVE와 종료 시각이 함께 옴, 종료 시각이 현재 이전.
	 *         UNAUTHENTICATED 요청자가 없거나 탈퇴. FORBIDDEN 본인 대상 또는 요청자의 역할 회수·정지.
	 *         NOT_FOUND 대상 없음. CONFLICT 탈퇴 회원, 이미 ACTIVE인 회원의 해제
	 */
	@Transactional
	public UserStatusUpdateResponse changeStatus(Long adminId, Long targetUserId, AdminUserStatusChange status,
			Instant suspendedUntil, String reason) {
		Objects.requireNonNull(status, "status");
		if (status == AdminUserStatusChange.ACTIVE && suspendedUntil != null) {
			throw new BusinessException(ErrorCode.INVALID_INPUT, "정지 해제에는 정지 종료 시각을 보낼 수 없습니다.");
		}
		if (Objects.equals(adminId, targetUserId)) {
			throw new BusinessException(ErrorCode.FORBIDDEN, "본인의 상태는 변경할 수 없습니다.");
		}
		String trimmedReason = reason.strip();
		lockRequesterAndTarget(adminId, targetUserId, Instant.now());

		ModerationResult result = switch (status) {
			case SUSPENDED -> moderationService.suspendByAdmin(adminId, targetUserId, suspendedUntil, trimmedReason);
			case ACTIVE -> moderationService.activate(adminId, targetUserId, trimmedReason);
		};
		return UserStatusUpdateResponse.from(result);
	}

	/**
	 * 두 행을 id 오름차순으로 잠그고 다시 읽는다. 잠그기 전에 다른 트랜잭션이 커밋한 값을 보기 위해서다
	 * ({@code UserRepository.findByIdForUpdate} 주석). 요청자 판정은 {@code AdminAccessInterceptor}와 같다(contracts §1.5).
	 *
	 * @return 잠근 대상
	 * @throws BusinessException UNAUTHENTICATED 요청자가 없거나 탈퇴. FORBIDDEN 요청자가 ADMIN이 아니거나 정지 중.
	 *         NOT_FOUND 대상 없음
	 */
	private User lockRequesterAndTarget(Long adminId, Long targetUserId, Instant now) {
		Optional<User> requester;
		Optional<User> target;
		if (targetUserId < adminId) {
			target = lock(targetUserId);
			requester = lock(adminId);
		}
		else {
			requester = lock(adminId);
			target = lock(targetUserId);
		}
		User lockedRequester = requester
				.filter(user -> !user.isWithdrawn())
				.orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
		if (lockedRequester.getRole() != UserRole.ADMIN || lockedRequester.isSuspendedAt(now)) {
			throw new BusinessException(ErrorCode.FORBIDDEN);
		}
		return target.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "회원을 찾을 수 없습니다."));
	}

	private Optional<User> lock(Long userId) {
		Optional<User> user = userRepository.findByIdForUpdate(userId);
		user.ifPresent(entityManager::refresh);
		return user;
	}

	/**
	 * 역할 회수는 이미 커밋되었다. 폐기 실패로 커밋된 조치가 500으로 응답되지 않게 삼키고 로그로 남긴다.
	 * 관리자 API는 DB 역할 재확인이 계속 막는다.
	 */
	private void revokeRefreshTokens(Long userId) {
		try {
			refreshTokenStore.revokeAll(userId);
		}
		catch (RuntimeException e) {
			log.error("역할 회수 회원의 Refresh Token 폐기 실패. 회수는 반영되었다. userId={}", userId, e);
		}
	}

	/** 감사 detail. 사유는 관리자 입력이라 남긴다(AuditEntry 규칙) */
	private static Map<String, Object> roleDetail(UserRole before, UserRole after, String reason) {
		Map<String, Object> detail = new LinkedHashMap<>();
		detail.put("before", before.name());
		detail.put("after", after.name());
		detail.put("reason", reason);
		return detail;
	}

}
