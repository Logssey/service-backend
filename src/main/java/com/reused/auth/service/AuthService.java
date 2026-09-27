package com.reused.auth.service;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.reused.audit.api.AuditLogger;
import com.reused.auth.client.OAuthProviderClient;
import com.reused.auth.dto.request.OAuthLoginRequest;
import com.reused.auth.dto.request.SignupRequest;
import com.reused.auth.dto.response.AuthTokenResponse;
import com.reused.auth.dto.response.OAuthLoginResponse;
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
 * 소셜 로그인·온보딩과 토큰 재발급·로그아웃.
 * 이메일 계정의 가입·로그인은 {@link EmailAuthService}가 담당한다.
 *
 * <p>감사 기록(recordSeparately)은 트랜잭션이 끝나 커넥션을 돌려준 뒤에 쓴다. 이유는 {@link EmailAuthService}와 같다
 * (요청 하나가 커넥션 두 개를 잡으면 동시 요청에 풀이 멈춘다). 그래서 로그인·재발급에는 트랜잭션을 두지 않고,
 * 가입은 {@link TransactionTemplate}으로 DB 작업만 감싼다. 로그인은 카카오 호출 동안에도 커넥션을 쥐지 않는다.
 */
@Service
public class AuthService {

	private static final Logger log = LoggerFactory.getLogger(AuthService.class);

	static final String EMAIL_CONSENT_REQUIRED_MESSAGE = "이메일을 등록하려면 이메일 수집·이용에 동의해야 합니다.";

	/** 같은 소셜 계정의 중복 가입. 이 두 제약 위반만 409다. */
	private static final List<String> DUPLICATE_SOCIAL_ACCOUNT_CONSTRAINTS = List.of(
			"uq_user_identities_provider_identity", "uq_user_identities_user_provider");

	private final List<OAuthProviderClient> providerClients;
	private final UserRepository userRepository;
	private final UserIdentityRepository identityRepository;
	private final NotificationSettingsRepository notificationSettingsRepository;
	private final JwtTokenProvider tokenProvider;
	private final RefreshTokenStore refreshTokenStore;
	private final AuditLogger auditLogger;
	private final EmailVerificationMailer verificationMailer;
	private final TransactionTemplate transaction;

	public AuthService(List<OAuthProviderClient> providerClients, UserRepository userRepository,
			UserIdentityRepository identityRepository,
			NotificationSettingsRepository notificationSettingsRepository,
			JwtTokenProvider tokenProvider, RefreshTokenStore refreshTokenStore, AuditLogger auditLogger,
			EmailVerificationMailer verificationMailer, PlatformTransactionManager transactionManager) {
		this.providerClients = providerClients;
		this.userRepository = userRepository;
		this.identityRepository = identityRepository;
		this.notificationSettingsRepository = notificationSettingsRepository;
		this.tokenProvider = tokenProvider;
		this.refreshTokenStore = refreshTokenStore;
		this.auditLogger = auditLogger;
		this.verificationMailer = verificationMailer;
		this.transaction = new TransactionTemplate(transactionManager);
	}

	/**
	 * 소셜 제공자의 인가 코드로 로그인하거나 가입 진입점을 반환한다.
	 *
	 * <p>인증 수단은 user_identities에서 찾는다(ADR-017). 탈퇴 시 인증 수단 행이 삭제되므로(ADR-018)
	 * 탈퇴 회원은 여기서 조회되지 않고 재가입 경로로 흘러간다. 이것이 정상 동작이다.
	 *
	 * @param providerPath 경로 변수 그대로의 제공자 이름(소문자)
	 */
	public LoginResult login(String providerPath, OAuthLoginRequest request) {
		AuthProvider provider = resolveProvider(providerPath);
		String providerUserId = clientFor(provider).fetchProviderUserId(request.code(), request.redirectUri());

		return identityRepository.findWithUserByProviderAndProviderUserId(provider, providerUserId)
				.map(identity -> loginExisting(identity.getUser(), provider))
				.orElseGet(() -> new LoginResult(
						OAuthLoginResponse.signupRequired(tokenProvider.issueSignupToken(provider, providerUserId)),
						null));
	}

	/**
	 * 경로 변수는 소문자만 받는다(소셜 로그인 명세). LOCAL은 소셜 제공자가 아니므로 제외한다.
	 * 해석할 수 없는 값은 지원하지 않는 제공자로 보고 404를 낸다.
	 */
	private static AuthProvider resolveProvider(String providerPath) {
		for (AuthProvider provider : AuthProvider.values()) {
			if (provider != AuthProvider.LOCAL && provider.name().toLowerCase(Locale.ROOT).equals(providerPath)) {
				return provider;
			}
		}
		throw new BusinessException(ErrorCode.NOT_FOUND, "지원하지 않는 로그인 제공자입니다.");
	}

	/**
	 * 구현체를 호출 시점에 고른다. 생성 시점에 맵으로 묶지 않는 이유는
	 * 테스트 대역의 {@code provider()} 스텁이 컨텍스트 생성 뒤에 설정되기 때문이다.
	 */
	private OAuthProviderClient clientFor(AuthProvider provider) {
		return providerClients.stream()
				.filter(client -> client.provider() == provider)
				.findFirst()
				.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "지원하지 않는 로그인 제공자입니다."));
	}

	/**
	 * 성공·실패를 모두 감사 로그에 남긴다(FR-LOG-001). 실패 기록은 업무 롤백과 무관해야 하므로 별도 트랜잭션이다.
	 */
	private LoginResult loginExisting(User user, AuthProvider provider) {
		// 인증 수단이 남아 있는 탈퇴 회원은 있을 수 없지만, 데이터 정합성이 깨진 경우의 방어다.
		if (user.isWithdrawn()) {
			auditLogger.recordSeparately(
					AuthAuditEntries.loginFailed(user.getId(), provider, AuthAuditEntries.REASON_WITHDRAWN));
			throw new BusinessException(ErrorCode.UNAUTHENTICATED);
		}
		// 소셜 로그인 명세의 오류 표를 따른다.
		// 다만 business-rules는 이용정지 중에도 로그인을 허용한다고 적고 있어 두 문서가 어긋난다.
		// 팀 합의 전까지 엔드포인트 명세(403)를 따르며, 뒤집을 때 고칠 곳은 이 한 줄이다.
		// 기간이 지난 정지는 자동 해제 작업이 상태를 되돌리기 전이어도 로그인을 허용한다.
		if (user.isSuspendedAt(Instant.now())) {
			auditLogger.recordSeparately(
					AuthAuditEntries.loginFailed(user.getId(), provider, AuthAuditEntries.REASON_SUSPENDED));
			throw new BusinessException(ErrorCode.USER_SUSPENDED);
		}

		String accessToken = tokenProvider.issueAccessToken(user.getId(), user.getRole());
		String refreshToken = refreshTokenStore.issue(user.getId());
		auditLogger.recordSeparately(AuthAuditEntries.loginSucceeded(user.getId(), provider));
		return new LoginResult(OAuthLoginResponse.login(accessToken, user), refreshToken);
	}

	/**
	 * 닉네임과 약관 동의를 받아 가입을 확정한다.
	 * users 행, signupToken에 담긴 제공자의 인증 수단 행, 알림 설정 행을 한 트랜잭션에서 만든다.
	 * 감사 기록은 그 트랜잭션이 커밋된 뒤에 한다({@link EmailAuthService#signup}과 같다).
	 *
	 * <p>이메일은 선택이다(ADR-016). 입력하면 별도 선택 동의가 필요하고(없으면 400), 동의 시각을 함께 저장한다.
	 * 커밋 뒤 소유 확인 메일을 1회 보내며, 발송 실패는 가입을 되돌리지 않는다. 입력하지 않으면 동의 값은 무시한다.
	 * 이 단계에서는 이메일 중복을 검사하지 않는다. 확인 전 주소는 선점할 수 없고, 가입 여부도 드러내지 않는다.
	 * 감사 기록에는 이메일도, 입력 여부도 남기지 않는다(NFR-LOG-003).
	 */
	public SignupResult signup(SignupRequest request) {
		NicknamePolicy.validate(request.nickname());
		String email = EmailAuthService.normalizeOptionalEmail(request.email());
		if (email != null && !Boolean.TRUE.equals(request.emailCollectionAgreed())) {
			throw new BusinessException(ErrorCode.INVALID_INPUT, EMAIL_CONSENT_REQUIRED_MESSAGE);
		}
		JwtTokenProvider.SignupTokenClaims signupClaims = tokenProvider.parseSignupToken(request.signupToken());

		CreatedSocialAccount account = transaction.execute(
				status -> createAccount(request.nickname(), email, signupClaims));

		// 새 users 행은 커밋 전이라 별도 트랜잭션에서 FK로 볼 수 없다. 커밋된 가입만 기록한다.
		AuthProvider provider = signupClaims.provider();
		AfterCommit.run(() -> auditLogger.recordSeparately(AuthAuditEntries.signedUp(account.userId(), provider)));
		if (email != null) {
			AfterCommit.run(() -> verificationMailer.sendQuietly(account.identityId(), email));
		}
		return account.result();
	}

	/**
	 * @param email 정규화된 선택 이메일. 입력하지 않았으면 null
	 */
	private CreatedSocialAccount createAccount(String nickname, String email,
			JwtTokenProvider.SignupTokenClaims signupClaims) {
		if (userRepository.existsByNickname(nickname)) {
			throw new BusinessException(ErrorCode.CONFLICT, "이미 사용 중인 닉네임입니다.");
		}

		// 필수 약관과 이메일 선택 동의는 같은 요청에서 받으므로 같은 시각으로 남긴다.
		Instant agreedAt = Instant.now();
		User user = User.signUp(nickname, agreedAt);
		try {
			user = userRepository.saveAndFlush(user);
		}
		catch (DataIntegrityViolationException e) {
			// 중복 검사와 INSERT 사이의 경쟁 조건. 최종 판정은 DB의 UNIQUE 제약이다.
			throw new BusinessException(ErrorCode.CONFLICT, "이미 사용 중인 닉네임입니다.", e);
		}
		UserIdentity identity;
		try {
			identity = identityRepository.saveAndFlush(UserIdentity.social(
					user, signupClaims.provider(), signupClaims.providerUserId(), email, agreedAt));
		}
		catch (DataIntegrityViolationException e) {
			if (DUPLICATE_SOCIAL_ACCOUNT_CONSTRAINTS.stream().anyMatch(name -> ConstraintNames.matches(e, name))) {
				// 같은 signupToken으로 온보딩을 두 번 완료하려는 경우
				throw new BusinessException(ErrorCode.CONFLICT, "이미 가입된 소셜 계정입니다.", e);
			}
			// 그 밖의 위반(예: 예상하지 못한 CHECK 위반)은 입력을 고쳐도 성공하지 않으므로 409로 보이면 안 된다.
			// 서버 DETAIL에는 행 값(이메일)이 담긴다. 드라이버 설정(logServerErrorDetail=false)이 예외 메시지에서 빼지만,
			// 설정에만 기대지 않고 제약 이름만 남기며 원 예외를 싣지 않는다(NFR-LOG-003).
			log.error("소셜 인증 수단 저장 실패. constraint={}", ConstraintNames.of(e));
			throw new BusinessException(ErrorCode.INTERNAL_ERROR);
		}
		notificationSettingsRepository.save(NotificationSettings.defaultsFor(user.getId()));

		// 토큰 발급(Redis)이 실패하면 가입도 되돌린다.
		String accessToken = tokenProvider.issueAccessToken(user.getId(), user.getRole());
		String refreshToken = refreshTokenStore.issue(user.getId());
		return new CreatedSocialAccount(user.getId(), identity.getId(),
				new SignupResult(AuthTokenResponse.of(accessToken, user), refreshToken));
	}

	/**
	 * Refresh Token 회전. 재사용이 감지되면 해당 사용자의 토큰이 전부 폐기된다.
	 *
	 * <p>트랜잭션을 두지 않는다. 재사용 탐지의 감사 기록이 커넥션을 쥔 채 커넥션을 하나 더 잡지 않게 한다.
	 * 회원 조회는 리포지토리 호출 하나로 끝난다.
	 */
	public RefreshResult refresh(String refreshToken) {
		RefreshTokenStore.Rotation rotation = refreshTokenStore.rotate(refreshToken);

		User user = userRepository.findById(rotation.userId())
				.orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
		if (user.isWithdrawn()) {
			refreshTokenStore.revokeAll(user.getId());
			throw new BusinessException(ErrorCode.UNAUTHENTICATED);
		}

		String accessToken = tokenProvider.issueAccessToken(user.getId(), user.getRole());
		return new RefreshResult(accessToken, rotation.refreshToken());
	}

	/**
	 * @param userId 로그아웃은 USER 인증이 필요하다. 감사 로그의 행위자다
	 * @param refreshToken 쿠키가 없으면 null. 폐기할 것이 없을 뿐 로그아웃은 성공한다
	 */
	public void logout(Long userId, String refreshToken) {
		if (refreshToken != null) {
			refreshTokenStore.revoke(refreshToken);
		}
		auditLogger.recordSeparately(AuthAuditEntries.loggedOut(userId));
	}

	public record LoginResult(OAuthLoginResponse response, String refreshToken) {
	}

	public record SignupResult(AuthTokenResponse response, String refreshToken) {
	}

	/** 온보딩 트랜잭션의 결과. 커밋 뒤 작업(감사·소유 확인 메일)이 식별자를 쓴다. */
	private record CreatedSocialAccount(Long userId, Long identityId, SignupResult result) {
	}

	public record RefreshResult(String accessToken, String refreshToken) {
	}

}
