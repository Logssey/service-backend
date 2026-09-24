package com.reused.auth.service;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
import com.reused.user.entity.AuthProvider;
import com.reused.user.entity.NotificationSettings;
import com.reused.user.entity.User;
import com.reused.user.entity.UserIdentity;
import com.reused.user.repository.NotificationSettingsRepository;
import com.reused.user.repository.UserIdentityRepository;
import com.reused.user.repository.UserRepository;

/**
 * 자체 이메일·비밀번호 계정(ADR-016, ADR-017).
 *
 * <p>계정 존재 여부가 응답으로 드러나지 않도록 한다(NFR-AUTH-018).
 * 로그인 실패는 이메일 미존재와 비밀번호 불일치를 같은 코드·메시지로 응답하고,
 * 재설정 요청은 계정 유무와 무관하게 204다.
 */
@Service
public class EmailAuthService {

	private static final Logger log = LoggerFactory.getLogger(EmailAuthService.class);

	static final String LOGIN_FAILED_MESSAGE = "이메일 또는 비밀번호가 올바르지 않습니다.";
	static final String INVALID_CODE_MESSAGE = "인증 코드가 올바르지 않거나 만료되었습니다.";

	private final UserRepository userRepository;
	private final UserIdentityRepository identityRepository;
	private final NotificationSettingsRepository notificationSettingsRepository;
	private final PasswordEncoder passwordEncoder;
	private final JwtTokenProvider tokenProvider;
	private final RefreshTokenStore refreshTokenStore;
	private final AuthCodeStore codeStore;
	private final LoginAttemptLimiter loginAttemptLimiter;
	private final AuthMailSender mailSender;

	/** 존재하지 않는 계정의 로그인에도 해시 검증 비용을 들여 응답 시간 차이를 없앤다(NFR-AUTH-018). */
	private final String dummyPasswordHash;

	public EmailAuthService(UserRepository userRepository, UserIdentityRepository identityRepository,
			NotificationSettingsRepository notificationSettingsRepository, PasswordEncoder passwordEncoder,
			JwtTokenProvider tokenProvider, RefreshTokenStore refreshTokenStore, AuthCodeStore codeStore,
			LoginAttemptLimiter loginAttemptLimiter, AuthMailSender mailSender) {
		this.userRepository = userRepository;
		this.identityRepository = identityRepository;
		this.notificationSettingsRepository = notificationSettingsRepository;
		this.passwordEncoder = passwordEncoder;
		this.tokenProvider = tokenProvider;
		this.refreshTokenStore = refreshTokenStore;
		this.codeStore = codeStore;
		this.loginAttemptLimiter = loginAttemptLimiter;
		this.mailSender = mailSender;
		this.dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
	}

	/**
	 * 단일 요청으로 가입을 확정하고 로그인 상태로 전환한다. 소유 확인 메일은 커밋 후 1회 자동 발송한다.
	 * 메일 발송 실패는 가입을 되돌리지 않는다 — 사용자가 재발송으로 복구할 수 있다.
	 */
	@Transactional
	public AuthResult signup(EmailSignupRequest request) {
		String email = normalizeEmail(request.email());

		if (identityRepository.existsByProviderAndEmail(AuthProvider.LOCAL, email)) {
			throw new BusinessException(ErrorCode.CONFLICT, "이미 가입된 이메일입니다.");
		}
		if (userRepository.existsByNickname(request.nickname())) {
			throw new BusinessException(ErrorCode.CONFLICT, "이미 사용 중인 닉네임입니다.");
		}

		User user;
		try {
			user = userRepository.saveAndFlush(User.signUp(request.nickname(), Instant.now()));
		}
		catch (DataIntegrityViolationException e) {
			throw new BusinessException(ErrorCode.CONFLICT, "이미 사용 중인 닉네임입니다.", e);
		}

		UserIdentity identity;
		try {
			identity = identityRepository.saveAndFlush(
					UserIdentity.local(user, email, passwordEncoder.encode(request.password())));
		}
		catch (DataIntegrityViolationException e) {
			// 중복 검사와 INSERT 사이의 경쟁 조건. 최종 판정은 부분 UNIQUE 인덱스다.
			throw new BusinessException(ErrorCode.CONFLICT, "이미 가입된 이메일입니다.", e);
		}
		notificationSettingsRepository.save(NotificationSettings.defaultsFor(user.getId()));

		Long identityId = identity.getId();
		afterCommit(() -> sendVerificationQuietly(identityId, email));

		return issueTokens(user);
	}

	/**
	 * 이메일 미존재와 비밀번호 불일치는 같은 401이다. 반복 실패는 계정 기준으로 제한한다(NFR-AUTH-019).
	 */
	@Transactional(readOnly = true)
	public AuthResult login(EmailLoginRequest request) {
		String email = normalizeEmail(request.email());
		UserIdentity identity = identityRepository.findByProviderAndEmail(AuthProvider.LOCAL, email).orElse(null);

		if (identity == null) {
			passwordEncoder.matches(request.password(), dummyPasswordHash);
			throw loginFailed();
		}

		loginAttemptLimiter.checkAllowed(identity.getId());
		if (!passwordEncoder.matches(request.password(), identity.getPasswordHash())) {
			loginAttemptLimiter.recordFailure(identity.getId());
			throw loginFailed();
		}
		loginAttemptLimiter.reset(identity.getId());

		User user = identity.getUser();
		if (user.isWithdrawn()) {
			throw loginFailed();
		}
		if (user.isSuspended()) {
			throw new BusinessException(ErrorCode.USER_SUSPENDED);
		}
		return issueTokens(user);
	}

	/**
	 * 소유 확인 코드 재발송. 대상 주소는 토큰 사용자의 LOCAL 인증 수단이며 바디로 주소를 받지 않는다.
	 */
	@Transactional(readOnly = true)
	public void resendVerification(Long userId) {
		UserIdentity identity = identityRepository.findByUserId(userId)
				.orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
		if (!identity.isLocal()) {
			throw new BusinessException(ErrorCode.CONFLICT, "소셜 계정은 이메일 소유 확인 대상이 아닙니다.");
		}
		if (identity.isEmailVerified()) {
			throw new BusinessException(ErrorCode.CONFLICT, "이미 소유 확인이 완료된 이메일입니다.");
		}

		codeStore.recordSend(identity.getId());
		String code = codeStore.issue(CodePurpose.VERIFY, identity.getId());
		mailSender.sendVerificationCode(identity.getEmail(), code);
	}

	@Transactional
	public void confirmVerification(Long userId, String code) {
		UserIdentity identity = identityRepository.findByUserId(userId)
				.orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));

		// 소셜 계정이나 이미 확인된 계정은 유효한 코드가 없으므로 같은 400으로 끝난다.
		codeStore.consume(CodePurpose.VERIFY, identity.getId(), code);
		identity.markEmailVerified(Instant.now());
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
	 */
	@Transactional
	public void confirmPasswordReset(PasswordResetConfirmRequest request) {
		String email = normalizeEmail(request.email());
		// 계정이 없으면 코드 불일치와 같은 400이다. 존재 여부를 드러내지 않는다.
		UserIdentity identity = identityRepository.findByProviderAndEmail(AuthProvider.LOCAL, email)
				.orElseThrow(() -> new BusinessException(ErrorCode.INVALID_INPUT, INVALID_CODE_MESSAGE));

		codeStore.consume(CodePurpose.RESET, identity.getId(), request.code());
		identity.changePasswordHash(passwordEncoder.encode(request.newPassword()));

		Long userId = identity.getUser().getId();
		Long identityId = identity.getId();
		afterCommit(() -> {
			refreshTokenStore.revokeAll(userId);
			loginAttemptLimiter.reset(identityId);
		});
	}

	private AuthResult issueTokens(User user) {
		String accessToken = tokenProvider.issueAccessToken(user.getId(), user.getRole());
		String refreshToken = refreshTokenStore.issue(user.getId());
		return new AuthResult(AuthTokenResponse.of(accessToken, user), refreshToken);
	}

	private void sendVerificationQuietly(Long identityId, String email) {
		try {
			codeStore.recordSend(identityId);
			String code = codeStore.issue(CodePurpose.VERIFY, identityId);
			mailSender.sendVerificationCode(email, code);
		}
		catch (RuntimeException e) {
			log.warn("가입 직후 소유 확인 메일 발송 실패. 재발송으로 복구 가능. identityId={}", identityId, e);
		}
	}

	/**
	 * 트랜잭션이 커밋된 뒤 실행한다. 롤백된 가입에 메일이 나가거나, 커밋되지 않은 비밀번호 변경보다
	 * 토큰 폐기가 먼저 일어나는 것을 막는다. 트랜잭션 밖이면 즉시 실행한다.
	 */
	private static void afterCommit(Runnable action) {
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			action.run();
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				action.run();
			}
		});
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

	public record AuthResult(AuthTokenResponse response, String refreshToken) {
	}

}
