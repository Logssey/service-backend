package com.reused.auth.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.reused.auth.config.KakaoProperties;
import com.reused.common.error.BusinessException;
import com.reused.common.error.ErrorCode;
import com.reused.user.entity.AuthProvider;

/**
 * 카카오 OAuth 실제 연동.
 *
 * <p>오류 매핑은 카카오 로그인 명세를 따른다.
 * 인가 코드가 잘못되면(카카오가 4xx 응답) {@code INVALID_INPUT}, 그 밖의 연동 실패는
 * {@code EXTERNAL_SERVICE_ERROR}다. 카카오 응답 본문은 사용자에게 그대로 전달하지 않는다.
 *
 * <p>{@code app.kakao.stub=true}이면 꺼지고 {@link KakaoStubClient}가 대신한다.
 */
@Component
@ConditionalOnProperty(name = "app.kakao.stub", havingValue = "false", matchIfMissing = true)
public class KakaoApiClient implements OAuthProviderClient {

	private static final Logger log = LoggerFactory.getLogger(KakaoApiClient.class);

	private final RestClient restClient;
	private final KakaoProperties properties;

	/**
	 * 제한 시간이 없으면 카카오 장애 시 요청 스레드가 무기한 묶인다(NFR-EXT-002). 연결·읽기 제한을 따로 건다.
	 * 제한 시간 초과는 {@link RestClientException}으로 와서 {@code EXTERNAL_SERVICE_ERROR}가 된다.
	 */
	public KakaoApiClient(RestClient.Builder builder, KakaoProperties properties) {
		HttpClientSettings settings = HttpClientSettings.defaults()
				.withTimeouts(properties.connectTimeout(), properties.readTimeout());
		this.restClient = builder
				.requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings))
				.build();
		this.properties = properties;
	}

	@Override
	public AuthProvider provider() {
		return AuthProvider.KAKAO;
	}

	@Override
	public String fetchProviderUserId(String code, String redirectUri) {
		String accessToken = exchangeCode(code, redirectUri);
		return fetchUserId(accessToken);
	}

	private String exchangeCode(String code, String redirectUri) {
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("grant_type", "authorization_code");
		form.add("client_id", properties.clientId());
		form.add("redirect_uri", redirectUri);
		form.add("code", code);
		if (StringUtils.hasText(properties.clientSecret())) {
			form.add("client_secret", properties.clientSecret());
		}

		try {
			KakaoTokenResponse response = restClient.post()
					.uri(properties.tokenUri())
					.contentType(MediaType.APPLICATION_FORM_URLENCODED)
					.body(form)
					.retrieve()
					.onStatus(status -> status.is4xxClientError(), (req, res) -> {
						log.warn("카카오 토큰 발급 거부. status={}", res.getStatusCode());
						throw new BusinessException(ErrorCode.INVALID_INPUT, "인가 코드가 유효하지 않습니다.");
					})
					.body(KakaoTokenResponse.class);

			if (response == null || !StringUtils.hasText(response.accessToken())) {
				throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR);
			}
			return response.accessToken();
		}
		catch (RestClientException e) {
			log.warn("카카오 토큰 발급 실패", e);
			throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR);
		}
	}

	private String fetchUserId(String accessToken) {
		try {
			KakaoUserResponse response = restClient.get()
					.uri(properties.userInfoUri())
					.header("Authorization", "Bearer " + accessToken)
					.retrieve()
					.body(KakaoUserResponse.class);

			if (response == null || response.id() == null) {
				throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR);
			}
			return String.valueOf(response.id());
		}
		catch (RestClientException e) {
			log.warn("카카오 사용자 정보 조회 실패", e);
			throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR);
		}
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record KakaoTokenResponse(@JsonProperty("access_token") String accessToken) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record KakaoUserResponse(Long id) {
	}

}
