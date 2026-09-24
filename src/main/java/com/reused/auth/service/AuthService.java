package com.reused.auth.service;

import java.time.Instant;
import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.reused.auth.client.OAuthProviderClient;
import com.reused.auth.dto.request.KakaoLoginRequest;
import com.reused.auth.dto.request.SignupRequest;
import com.reused.auth.dto.response.AuthTokenResponse;
import com.reused.auth.dto.response.KakaoLoginResponse;
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
 * 소셜(카카오) 로그인·온보딩과 토큰 재발급·로그아웃.
 * 이메일 계정의 가입·로그인은 {@link EmailAuthService}가 담당한다.
 */
@Service
public class AuthService {

	private final List<OAuthProviderClient> providerClients;
	private final UserRepository userRepository;
	private final UserIdentityRepository identityRepository;
	private final NotificationSettingsRepository notificationSettingsRepository;
	private final JwtTokenProvider tokenProvider;
	private final RefreshTokenStore refreshTokenStore;

	public AuthService(List<OAuthProviderClient> providerClients, UserRepository userRepository,
			UserIdentityRepository identityRepository,
			NotificationSettingsRepository notificationSettingsRepository,
			JwtTokenProvider tokenProvider, RefreshTokenStore refreshTokenStore) {
		this.providerClients = providerClients;
		this.userRepository = userRepository;
		this.identityRepository = identityRepository;
		this.notificationSettingsRepository = notificationSettingsRepository;
		this.tokenProvider = tokenProvider;
		this.refreshTokenStore = refreshTokenStore;
	}

	/**
	 * 카카오 인가 코드로 로그인하거나 가입 진입점을 반환한다.
	 *
	 * <p>인증 수단은 user_identities에서 찾는다(ADR-017). 탈퇴 시 인증 수단 행이 삭제되므로(ADR-018)
	 * 탈퇴 회원은 여기서 조회되지 않고 재가입 경로로 흘러간다. 이것이 정상 동작이다.
	 */
	@Transactional
	public LoginResult login(KakaoLoginRequest request) {
		AuthProvider provider = AuthProvider.KAKAO;
		String providerUserId = clientFor(provider).fetchProviderUserId(request.code(), request.redirectUri());

		return identityRepository.findByProviderAndProviderUserId(provider, providerUserId)
				.map(identity -> loginExisting(identity.getUser()))
				.orElseGet(() -> new LoginResult(
						KakaoLoginResponse.signupRequired(tokenProvider.issueSignupToken(provider, providerUserId)),
						null));
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

	private LoginResult loginExisting(User user) {
		// 인증 수단이 남아 있는 탈퇴 회원은 있을 수 없지만, 데이터 정합성이 깨진 경우의 방어다.
		if (user.isWithdrawn()) {
			throw new BusinessException(ErrorCode.UNAUTHENTICATED);
		}
		// 카카오 로그인 명세의 오류 표를 따른다.
		// 다만 business-rules는 이용정지 중에도 로그인을 허용한다고 적고 있어 두 문서가 어긋난다.
		// 팀 합의 전까지 엔드포인트 명세(403)를 따르며, 뒤집을 때 고칠 곳은 이 한 줄이다.
		if (user.isSuspended()) {
			throw new BusinessException(ErrorCode.USER_SUSPENDED);
		}

		String accessToken = tokenProvider.issueAccessToken(user.getId(), user.getRole());
		String refreshToken = refreshTokenStore.issue(user.getId());
		return new LoginResult(KakaoLoginResponse.login(accessToken, user), refreshToken);
	}

	/**
	 * 닉네임과 약관 동의를 받아 가입을 확정한다.
	 * users 행, signupToken에 담긴 제공자의 인증 수단 행, 알림 설정 행을 한 트랜잭션에서 만든다.
	 */
	@Transactional
	public SignupResult signup(SignupRequest request) {
		JwtTokenProvider.SignupTokenClaims signupClaims = tokenProvider.parseSignupToken(request.signupToken());

		if (userRepository.existsByNickname(request.nickname())) {
			throw new BusinessException(ErrorCode.CONFLICT, "이미 사용 중인 닉네임입니다.");
		}

		User user = User.signUp(request.nickname(), Instant.now());
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

		String accessToken = tokenProvider.issueAccessToken(user.getId(), user.getRole());
		String refreshToken = refreshTokenStore.issue(user.getId());
		return new SignupResult(AuthTokenResponse.of(accessToken, user), refreshToken);
	}

	/**
	 * Refresh Token 회전. 재사용이 감지되면 해당 사용자의 토큰이 전부 폐기된다.
	 */
	@Transactional(readOnly = true)
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

	public void logout(String refreshToken) {
		if (refreshToken != null) {
			refreshTokenStore.revoke(refreshToken);
		}
	}

	public record LoginResult(KakaoLoginResponse response, String refreshToken) {
	}

	public record SignupResult(AuthTokenResponse response, String refreshToken) {
	}

	public record RefreshResult(String accessToken, String refreshToken) {
	}

}
