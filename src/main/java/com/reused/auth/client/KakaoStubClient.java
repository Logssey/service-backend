package com.reused.auth.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.reused.common.error.BusinessException;
import com.reused.common.error.ErrorCode;
import com.reused.user.entity.AuthProvider;

/**
 * 로컬 개발용 카카오 대역. 카카오를 호출하지 않고 인가 코드를 그대로 회원번호로 쓴다.
 *
 * <p>프론트 {@code VITE_KAKAO_STUB=true}와 짝을 이룬다. 프론트가 브라우저마다 고정한 코드를 보내므로
 * 같은 브라우저에서는 같은 계정으로 계속 로그인된다.
 *
 * <p>{@code local} 프로파일과 {@code app.kakao.stub=true}가 모두 있어야 뜬다. 이 모드에서는
 * {@link KakaoApiClient}가 꺼지므로, 프로파일 없이 stub만 켜면 카카오 로그인은 404로 막힌다.
 */
@Component
@Profile("local")
@ConditionalOnProperty(name = "app.kakao.stub", havingValue = "true")
public class KakaoStubClient implements OAuthProviderClient {

	private static final Logger log = LoggerFactory.getLogger(KakaoStubClient.class);

	/** user_identities.provider_user_id 길이 */
	private static final int MAX_LENGTH = 255;

	public KakaoStubClient() {
		log.warn("카카오 대역 클라이언트가 켜져 있다. 인가 코드를 검증 없이 회원번호로 쓴다. 로컬 개발 전용이다.");
	}

	@Override
	public AuthProvider provider() {
		return AuthProvider.KAKAO;
	}

	@Override
	public String fetchProviderUserId(String code, String redirectUri) {
		if (code == null || code.isBlank() || code.length() > MAX_LENGTH) {
			throw new BusinessException(ErrorCode.INVALID_INPUT, "인가 코드가 유효하지 않습니다.");
		}
		return code;
	}

}
