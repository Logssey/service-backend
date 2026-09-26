package com.reused.auth.service;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.reused.audit.api.AuditLogger;
import com.reused.auth.code.AuthCodeStore;
import com.reused.auth.code.CodePurpose;
import com.reused.auth.code.LoginAttemptLimiter;
import com.reused.auth.dto.request.EmailLoginRequest;
import com.reused.auth.dto.request.EmailSignupRequest;
import com.reused.auth.dto.request.PasswordResetConfirmRequest;
import com.reused.auth.dto.response.AuthTokenResponse;
import com.reused.auth.mail.AuthMailSender;
import com.reused.auth.token.JwtTokenProvider;
import com.reused.auth.token.RefreshTokenStore;
import com.reused.common.error.BusinessException;
import com.reused.common.error.ErrorCode;
import com.reused.common.tx.AfterCommit;
import com.reused.user.entity.AuthProvider;
import com.reused.user.entity.NotificationSettings;
import com.reused.user.entity.User;
import com.reused.user.entity.UserIdentity;
import com.reused.user.repository.NotificationSettingsRepository;
import com.reused.user.repository.UserIdentityRepository;
import com.reused.user.repository.UserRepository;
import com.reused.user.service.NicknamePolicy;

/**
 * 자체 이메일·비밀번호 계정(ADR-016, ADR-017).
 *
 * <p>계정 존재 여부가 응답으로 드러나지 않도록 한다(NFR-AUTH-018).
 * 로그인 실패는 이메일 미존재와 비밀번호 불일치를 같은 코드·메시지로 응답하고,
 * 재설정 요청은 계정 유무와 무관하게 204다.
 *
 * <p>감사 기록(recordSeparately)은 트랜잭션이 끝나 커넥션을 돌려준 뒤에 쓴다. 트랜잭션 안(커밋 뒤 콜백 포함)에서
 * 쓰면 요청 하나가 커넥션을 두 개 잡는다. 공개 엔드포인트라 동시 요청이 풀 크기만큼 몰리면 모두 두 번째 커넥션을
 * 기다리며 풀이 멈추고, 대기 시간이 지나면 실패 기록이 사라진다. 그래서 로그인에는 트랜잭션을 두지 않고,
 * 가입·재설정은 {@link TransactionTemplate}으로 DB 작업만 감싼다.
 *
 * <p>소유 확인(재발송·확인)은 이메일이 등록된 모든 인증 수단에 적용한다. LOCAL은 항상, 소셜은 온보딩에서 이메일을
 * 입력했을 때다(ADR-019). 이메일 로그인·가입 중복 검사·비밀번호 재설정·비밀번호 변경은 계속 LOCAL 전용이다.
 */
@Service
public class EmailAuthService {

	private static final Logger log = LoggerFactory.getLogger(EmailAuthService.class);

	static final String LOGIN_FAILED_MESSAGE = "이메일 또는 비밀번호가 올바르지 않습니다.";
	static final String INVALID_CODE_MESSAGE = "인증 코드가 올바르지 않거나 만료되었습니다.";
	static final String CURRENT_PASSWORD_MISMATCH_MESSAGE = "현재 비밀번호가 올바르지 않습니다.";
	static final String PASSWORD_CHANGE_RATE_LIMITED_MESSAGE = "비밀번호 확인 시도가 너무 많습니다. 잠시 후 다시 시도해 주세요.";
	static final String EMAIL_VERIFIED_ELSEWHERE_MESSAGE = "이미 다른 계정에서 인증된 이메일입니다.";

	/** 소유 확인을 마친 소셜 이메일의 유일성(004). 확인 판정의 경합을 최종 판정한다. */
	private static final String SOCIAL_VERIFIED_EMAIL_INDEX = "uq_user_identities_email_social_verified";

	private final UserRepository userRepository;
	private final UserIdentityRepository identityRepository;
	private final NotificationSettingsRepository notificationSettingsRepository;
	private final PasswordEncoder passwordEncoder;
	private final JwtTokenProvider tokenProvider;
	private final RefreshTokenStore refreshTokenStore;
	private final AuthCodeStore codeStore;
	private final LoginAttemptLimiter loginAttemptLimiter;
	private final AuthMailSender mailSender;
	private final EmailVerificationMailer verificationMailer;
	private final AuditLogger auditLogger;
	private final TransactionTemplate transaction;

	/** 존재하지 않는 계정의 로그인에도 해시 검증 비용을 들여 응답 시간 차이를 없앤다(NFR-AUTH-018). */
	private final String dummyPasswordHash;

	public EmailAuthService(UserRepository userRepository, UserIdentityRepository identityRepository,
			NotificationSettingsRepository notificationSettingsRepository, PasswordEncoder passwordEncoder,
			JwtTokenProvider tokenProvider, RefreshTokenStore refreshTokenStore, AuthCodeStore codeStore,
			LoginAttemptLimiter loginAttemptLimiter, AuthMailSender mailSender,
			EmailVerificationMailer verificationMailer, AuditLogger auditLogger,
			PlatformTransactionManager transactionManager) {
		this.userRepository = userRepository;
		this.identityRepository = identityRepository;
		this.notificationSettingsRepository = notificationSettingsRepository;
		this.passwordEncoder = passwordEncoder;
		this.tokenProvider = tokenProvider;
		this.refreshTokenStore = refreshTokenStore;
		this.codeStore = codeStore;
		this.loginAttemptLimiter = loginAttemptLimiter;
		this.mailSender = mailSender;
		this.verificationMailer = verificationMailer;
		this.auditLogger = auditLogger;
		this.transaction = new TransactionTemplate(transactionManager);
		this.dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
	}

	/**
	 * 단일 요청으로 가입을 확정하고 로그인 상태로 전환한다. 소유 확인 메일은 커밋 후 1회 자동 발송한다.
	 * 메일 발송 실패는 가입을 되돌리지 않는다 — 사용자가 재발송으로 복구할 수 있다.
	 *
	 * <p>트랜잭션은 계정 행 생성과 토큰 발급까지다. 비밀번호 해시는 그 전에, 감사 기록과 메일은 그 뒤에 한다.
	 * 호출자 트랜잭션이 없으면(컨트롤러 경로) {@link AfterCommit}이 바로 실행하고, 있으면 그 커밋 뒤로 미룬다.
	 */
	public AuthResult signup(EmailSignupRequest request) {
		NicknamePolicy.validate(request.nickname());
		String email = normalizeEmail(request.email());
		String passwordHash = passwordEncoder.encode(request.password());

		CreatedAccount account = transaction.execute(status -> createAccount(request.nickname(), email, passwordHash));

		// 새 users 행은 커밋 전이라 별도 트랜잭션에서 FK로 볼 수 없다. 커밋된 가입만 기록한다.
		AfterCommit.run(() -> auditLogger.recordSeparately(
				AuthAuditEntries.signedUp(account.userId(), AuthProvider.LOCAL)));
		AfterCommit.run(() -> verificationMailer.sendQuietly(account.identityId(), email));
		return account.result();
	}

	private CreatedAccount createAccount(String nickname, String email, String passwordHash) {
		if (identityRepository.existsByProviderAndEmail(AuthProvider.LOCAL, email)) {
			throw new BusinessException(ErrorCode.CONFLICT, "이미 가입된 이메일입니다.");
		}
		if (userRepository.existsByNickname(nickname)) {
			throw new BusinessException(ErrorCode.CONFLICT, "이미 사용 중인 닉네임입니다.");
		}

		User user;
		try {
			user = userRepository.saveAndFlush(User.signUp(nickname, Instant.now()));
		}
		catch (DataIntegrityViolationException e) {
			throw new BusinessException(ErrorCode.CONFLICT, "이미 사용 중인 닉네임입니다.", e);
		}

		UserIdentity identity;
		try {
			identity = identityRepository.saveAndFlush(UserIdentity.local(user, email, passwordHash));
		}
		catch (DataIntegrityViolationException e) {
			// 중복 검사와 INSERT 사이의 경쟁 조건. 최종 판정은 부분 UNIQUE 인덱스다.
			throw new BusinessException(ErrorCode.CONFLICT, "이미 가입된 이메일입니다.", e);
		}
		notificationSettingsRepository.save(NotificationSettings.defaultsFor(user.getId()));

		// 토큰 발급(Redis)이 실패하면 가입도 되돌린다. 계정만 생기고 로그인되지 않는 상태를 남기지 않는다.
		return new CreatedAccount(user.getId(), identity.getId(), issueTokens(user));
	}

	/**
	 * 이메일 미존재와 비밀번호 불일치는 같은 401이다. 반복 실패는 계정 기준으로 제한한다(NFR-AUTH-019).
	 *
	 * <p>성공·실패를 모두 감사 로그에 남긴다(FR-LOG-001). 트랜잭션을 두지 않는다. 인증 수단은 회원과 함께 한 번에 읽고,
	 * 해시 검증과 감사 기록은 커넥션을 쥐지 않은 채 한다.
	 * 실패 사유는 감사 로그에만 구분해 남고 응답은 여전히 구분되지 않는다. 이메일은 기록하지 않는다.
	 */
	public AuthResult login(EmailLoginRequest request) {
		String email = normalizeEmail(request.email());
		UserIdentity identity = identityRepository.findWithUserByProviderAndEmail(AuthProvider.LOCAL, email)
				.orElse(null);

		if (identity == null) {
			passwordEncoder.matches(request.password(), dummyPasswordHash);
			recordLoginFailure(null, AuthAuditEntries.REASON_UNKNOWN_ACCOUNT);
			throw loginFailed();
		}

		Long userId = identity.getUser().getId();
		// 시도는 검증 전에 센다. 실패하면 센 그대로 남고, 성공하면 초기화한다.
		try {
			loginAttemptLimiter.acquire(identity.getId());
		}
		catch (BusinessException e) {
			recordLoginFailure(userId, AuthAuditEntries.REASON_RATE_LIMITED);
			throw e;
		}
		if (!passwordEncoder.matches(request.password(), identity.getPasswordHash())) {
			recordLoginFailure(userId, AuthAuditEntries.REASON_BAD_CREDENTIALS);
			throw loginFailed();
		}
		loginAttemptLimiter.reset(identity.getId());

		User user = identity.getUser();
		if (user.isWithdrawn()) {
			recordLoginFailure(userId, AuthAuditEntries.REASON_WITHDRAWN);
			throw loginFailed();
		}
		// 기간이 지난 정지는 자동 해제 작업이 상태를 되돌리기 전이어도 로그인을 허용한다.
		if (user.isSuspendedAt(Instant.now())) {
			recordLoginFailure(userId, AuthAuditEntries.REASON_SUSPENDED);
			throw new BusinessException(ErrorCode.USER_SUSPENDED);
		}
		AuthResult result = issueTokens(user);
		auditLogger.recordSeparately(AuthAuditEntries.loginSucceeded(userId, AuthProvider.LOCAL));
		return result;
	}

	/**
	 * 소유 확인 코드 재발송. 대상 주소는 토큰 사용자의 인증 수단에 등록된 이메일이며 바디로 주소를 받지 않는다.
	 * 이메일을 입력하지 않은 소셜 계정은 대상이 없어 409다. 같은 주소를 다른 소셜 계정이 이미 확인했어도 재발송은
	 * 막지 않는다. 그 판정은 코드로 소유가 증명된 뒤 {@link #confirmVerification}에서 한다(ADR-019).
	 *
	 * <p>트랜잭션을 두지 않는다({@link #requestPasswordReset}과 같다). 인증 수단은 기본 컬럼만 쓰므로 조회 한 번으로 끝나고,
	 * Redis 기록과 동기 SMTP 발송(단계마다 최대 5초)은 커넥션을 쥐지 않은 채 한다.
	 */
	public void resendVerification(Long userId) {
		UserIdentity identity = identityRepository.findByUserId(userId)
				.orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
		if (!identity.hasEmail()) {
			throw new BusinessException(ErrorCode.CONFLICT, "등록된 이메일이 없습니다.");
		}
		if (identity.isEmailVerified()) {
			throw new BusinessException(ErrorCode.CONFLICT, "이미 소유 확인이 완료된 이메일입니다.");
		}

		verificationMailer.send(identity.getId(), identity.getEmail());
	}

	/**
	 * 발송된 코드로 인증 수단에 등록된 이메일의 소유를 확인한다.
	 *
	 * <p>소유 확인을 마친 소셜 이메일은 소셜 인증 수단 사이에서 하나뿐이다(ADR-019). 다른 소셜 계정이 먼저 확인을 마친
	 * 주소면 409다. 코드가 맞아 소유가 증명된 뒤에만 알리므로 주소 주인이 아닌 사람에게 가입 여부를 드러내지 않고
	 * (NFR-AUTH-018), 이때 코드는 소비하지 않는다. 검사와 저장 사이의 경합은 부분 UNIQUE 인덱스가 최종 판정하며
	 * 같은 409로 바꾼다. LOCAL 이메일은 이 판정 대상이 아니다(제공자가 다르면 별개 계정, ADR-016).
	 *
	 * @throws BusinessException INVALID_INPUT 코드 불일치·만료(이메일이 없거나 이미 확인된 계정 포함),
	 *         RATE_LIMITED 코드당 시도 초과, CONFLICT 다른 소셜 계정에서 이미 확인된 이메일
	 */
	@Transactional
	public void confirmVerification(Long userId, String code) {
		UserIdentity identity = identityRepository.findByUserId(userId)
				.orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));

		// 이메일이 없는 계정이나 이미 확인된 계정은 유효한 코드가 없으므로 같은 400으로 끝난다.
		codeStore.check(CodePurpose.VERIFY, identity.getId(), code);
		if (!identity.isLocal()
				&& identityRepository.existsVerifiedSocialEmailElsewhere(identity.getEmail(), identity.getId())) {
			throw new BusinessException(ErrorCode.CONFLICT, EMAIL_VERIFIED_ELSEWHERE_MESSAGE);
		}

		identity.markEmailVerified(Instant.now());
		try {
			identityRepository.flush();
		}
		catch (DataIntegrityViolationException e) {
			if (ConstraintNames.matches(e, SOCIAL_VERIFIED_EMAIL_INDEX)) {
				throw new BusinessException(ErrorCode.CONFLICT, EMAIL_VERIFIED_ELSEWHERE_MESSAGE);
			}
			// 서버 DETAIL에는 행 값(이메일)이 담긴다. 드라이버 설정(logServerErrorDetail=false)이 예외 메시지에서 빼지만,
			// 설정에만 기대지 않고 제약 이름만 남기며 원 예외를 싣지 않는다(NFR-LOG-003).
			log.error("이메일 소유 확인 저장 실패. constraint={}", ConstraintNames.of(e));
			throw new BusinessException(ErrorCode.INTERNAL_ERROR);
		}
		codeStore.discard(CodePurpose.VERIFY, identity.getId());
	}

	/**
	 * 계정 유무와 무관하게 정상 종료한다. 코드는 실제로 존재하는 LOCAL 계정에만 발송한다.
	 */
	public void requestPasswordReset(String rawEmail) {
		String email = normalizeEmail(rawEmail);
		UserIdentity identity = identityRepository.findByProviderAndEmail(AuthProvider.LOCAL, email).orElse(null);
		if (identity == null) {
			return;
		}

		codeStore.recordSend(identity.getId());
		String code = codeStore.issue(CodePurpose.RESET, identity.getId());
		mailSender.sendPasswordResetCode(email, code);
	}

	/**
	 * 성공하면 해당 사용자의 Refresh Token을 전부 폐기한다(NFR-AUTH-016). 다른 기기의 세션이 함께 끊긴다.
	 *
	 * <p>트랜잭션은 비밀번호 변경까지다. 감사 기록과 Redis 작업은 커밋 뒤에 한다({@link #signup}과 같다).
	 */
	public void confirmPasswordReset(PasswordResetConfirmRequest request) {
		String email = normalizeEmail(request.email());
		ResetAccount account = transaction.execute(status -> resetPassword(email, request));

		// 감사 기록은 스스로 실패를 삼키므로 먼저 등록한다. Redis 작업이 실패해도 기록은 남는다.
		AfterCommit.run(() -> auditLogger.recordSeparately(AuthAuditEntries.passwordReset(account.userId())));
		AfterCommit.run(() -> {
			refreshTokenStore.revokeAll(account.userId());
			loginAttemptLimiter.reset(account.identityId());
		});
	}

	/**
	 * 로그인한 LOCAL 계정의 비밀번호 변경. 성공하면 이 회원의 Refresh Token을 전부 폐기하고 새 토큰은 주지 않는다
	 * (NFR-AUTH-016, 비밀번호 변경 명세 "204 본문 없음"). 현재 기기도 Access Token이 만료되면 다시 로그인한다.
	 *
	 * <p>현재 비밀번호 추측을 로그인과 같은 제한기({@link LoginAttemptLimiter}, 계정 기준 10분당 5회)로 막는다.
	 * 문서에 없는 429다. 시도는 검증 전에 로그인과 함께 센다(동시 요청으로 한도를 넘지 못한다). 성공하면 초기화한다.
	 *
	 * <p>트랜잭션은 해시 교체 한 번이다. 해시 검증·계산과 감사 기록은 커넥션을 쥐지 않은 채 한다({@link #login}과 같은 이유).
	 * 이용정지 회원도 변경할 수 있다(문서에 403 없음).
	 *
	 * @throws BusinessException UNAUTHENTICATED 없음·탈퇴·인증 수단 없음·현재 비밀번호 불일치,
	 *         CONFLICT 소셜 계정, RATE_LIMITED 불일치가 한도에 도달함
	 */
	public void changePassword(Long userId, String currentPassword, String newPassword) {
		userRepository.findById(userId)
				.filter(user -> !user.isWithdrawn())
				.orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
		UserIdentity identity = identityRepository.findByUserId(userId)
				.orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
		// 입력을 고쳐도 성공할 수 없는 요청이라 비밀번호 확인보다 먼저 409로 끝낸다(비밀번호 변경 명세).
		if (!identity.isLocal()) {
			throw new BusinessException(ErrorCode.CONFLICT, "소셜 계정은 변경할 비밀번호가 없습니다.");
		}

		Long identityId = identity.getId();
		try {
			loginAttemptLimiter.acquire(identityId);
		}
		catch (BusinessException e) {
			auditLogger.recordSeparately(
					AuthAuditEntries.passwordChangeFailed(userId, AuthAuditEntries.REASON_RATE_LIMITED));
			throw new BusinessException(ErrorCode.RATE_LIMITED, PASSWORD_CHANGE_RATE_LIMITED_MESSAGE, e);
		}
		if (!passwordEncoder.matches(currentPassword, identity.getPasswordHash())) {
			auditLogger.recordSeparately(
					AuthAuditEntries.passwordChangeFailed(userId, AuthAuditEntries.REASON_BAD_CREDENTIALS));
			throw new BusinessException(ErrorCode.UNAUTHENTICATED, CURRENT_PASSWORD_MISMATCH_MESSAGE);
		}

		String newPasswordHash = passwordEncoder.encode(newPassword);
		transaction.executeWithoutResult(status -> replacePasswordHash(identityId, newPasswordHash));

		// 감사 기록은 스스로 실패를 삼키므로 먼저 등록한다. Redis 작업이 실패해도 기록은 남는다.
		AfterCommit.run(() -> auditLogger.recordSeparately(AuthAuditEntries.passwordChanged(userId)));
		AfterCommit.run(() -> {
			refreshTokenStore.revokeAll(userId);
			loginAttemptLimiter.reset(identityId);
		});
	}

	/**
	 * 확인한 뒤 교체하기 전에 탈퇴가 커밋되었으면 인증 수단 행이 없다. 탈퇴 회원과 같은 401이다.
	 */
	private void replacePasswordHash(Long identityId, String newPasswordHash) {
		UserIdentity identity = identityRepository.findById(identityId)
				.orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
		identity.changePasswordHash(newPasswordHash);
	}

	private ResetAccount resetPassword(String email, PasswordResetConfirmRequest request) {
		// 계정이 없으면 코드 불일치와 같은 400이다. 존재 여부를 드러내지 않는다.
		UserIdentity identity = identityRepository.findByProviderAndEmail(AuthProvider.LOCAL, email)
				.orElseThrow(() -> new BusinessException(ErrorCode.INVALID_INPUT, INVALID_CODE_MESSAGE));

		codeStore.consume(CodePurpose.RESET, identity.getId(), request.code());
		identity.changePasswordHash(passwordEncoder.encode(request.newPassword()));
		return new ResetAccount(identity.getUser().getId(), identity.getId());
	}

	private AuthResult issueTokens(User user) {
		String accessToken = tokenProvider.issueAccessToken(user.getId(), user.getRole());
		String refreshToken = refreshTokenStore.issue(user.getId());
		return new AuthResult(AuthTokenResponse.of(accessToken, user), refreshToken);
	}

	private void recordLoginFailure(Long userId, String reason) {
		auditLogger.recordSeparately(AuthAuditEntries.loginFailed(userId, AuthProvider.LOCAL, reason));
	}

	private static BusinessException loginFailed() {
		return new BusinessException(ErrorCode.UNAUTHENTICATED, LOGIN_FAILED_MESSAGE);
	}

	/**
	 * 앞뒤 공백을 제거하고 소문자로 통일한다. 저장·조회 모두 이 값을 쓴다.
	 */
	static String normalizeEmail(String email) {
		return email.trim().toLowerCase(Locale.ROOT);
	}

	/**
	 * 선택 입력 이메일(소셜 온보딩, ADR-019). null·빈 문자열은 입력하지 않은 것으로 보고 null을 돌려준다.
	 * 공백만 있는 값은 요청 검증({@code @Email})에서 이미 400이다.
	 */
	static String normalizeOptionalEmail(String email) {
		return email == null || email.isBlank() ? null : normalizeEmail(email);
	}

	public record AuthResult(AuthTokenResponse response, String refreshToken) {
	}

	/** 가입 트랜잭션의 결과. 커밋 뒤 작업(감사·메일)이 식별자를 쓴다. */
	private record CreatedAccount(Long userId, Long identityId, AuthResult result) {
	}

	/** 재설정 트랜잭션의 결과. 커밋 뒤 작업(감사·토큰 폐기·시도 횟수 초기화)이 쓴다. */
	private record ResetAccount(Long userId, Long identityId) {
	}

}
