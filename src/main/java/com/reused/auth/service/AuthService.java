package com.reused.auth.service;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

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

	private final List<OAuthProviderClient> providerClients;
	private final UserRepository userRepository;
	private final UserIdentityRepository identityRepository;
	private final NotificationSettingsRepository notificationSettingsRepository;
	private final JwtTokenProvider tokenProvider;
	private final RefreshTokenStore refreshTokenStore;
	private final AuditLogger auditLogger;
	private final TransactionTemplate transaction;

	public AuthService(List<OAuthProviderClient> providerClients, UserRepository userRepository,
			UserIdentityRepository identityRepository,
			NotificationSettingsRepository notificationSettingsRepository,
			JwtTokenProvider tokenProvider, RefreshTokenStore refreshTokenStore, AuditLogger auditLogger,
			PlatformTransactionManager transactionManager) {
		this.providerClients = providerClients;
		this.userRepository = userRepository;
		this.identityRepository = identityRepository;
		this.notificationSettingsRepository = notificationSettingsRepository;
		this.tokenProvider = tokenProvider;
		this.refreshTokenStore = refreshTokenStore;
		this.auditLogger = auditLogger;
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
	 */
	public SignupResult signup(SignupRequest request) {
		NicknamePolicy.validate(request.nickname());
		JwtTokenProvider.SignupTokenClaims signupClaims = tokenProvider.parseSignupToken(request.signupToken());

		SignupResult result = transaction.execute(status -> createAccount(request.nickname(), signupClaims));

		// 새 users 행은 커밋 전이라 별도 트랜잭션에서 FK로 볼 수 없다. 커밋된 가입만 기록한다.
		Long userId = result.response().user().userId();
		AuthProvider provider = signupClaims.provider();
		AfterCommit.run(() -> auditLogger.recordSeparately(AuthAuditEntries.signedUp(userId, provider)));
		return result;
	}

	private SignupResult createAccount(String nickname, JwtTokenProvider.SignupTokenClaims signupClaims) {
		if (userRepository.existsByNickname(nickname)) {
			throw new BusinessException(ErrorCode.CONFLICT, "이미 사용 중인 닉네임입니다.");
		}

		User user = User.signUp(nickname, Instant.now());
		try {
			user = userRepository.saveAndFlush(user);
		}
		catch (DataIntegrityViolationException e) {
			// 중복 검사와 INSERT 사이의 경쟁 조건. 최종 판정은 DB의 UNIQUE 제약이다.
			throw new BusinessException(ErrorCode.CONFLICT, "이미 사용 중인 닉네임입니다.", e);
		}
		try {
			identityRepository.saveAndFlush(
					UserIdentity.social(user, signupClaims.provider(), signupClaims.providerUserId()));
		}
		catch (DataIntegrityViolationException e) {
			// 같은 signupToken으로 온보딩을 두 번 완료하려는 경우
			throw new BusinessException(ErrorCode.CONFLICT, "이미 가입된 소셜 계정입니다.", e);
		}
		notificationSettingsRepository.save(NotificationSettings.defaultsFor(user.getId()));

		// 토큰 발급(Redis)이 실패하면 가입도 되돌린다.
		String accessToken = tokenProvider.issueAccessToken(user.getId(), user.getRole());
		String refreshToken = refreshTokenStore.issue(user.getId());
		return new SignupResult(AuthTokenResponse.of(accessToken, user), refreshToken);
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

	public record RefreshResult(String accessToken, String refreshToken) {
	}

}
