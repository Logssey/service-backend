package com.reused.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.web.client.RestClient;

import com.reused.auth.client.KakaoApiClient;
import com.reused.auth.config.KakaoProperties;
import com.reused.common.error.BusinessException;
import com.reused.common.error.ErrorCode;

/**
 * 카카오 호출 제한 시간(NFR-EXT-002). 연결은 받지만 응답하지 않는 로컬 소켓으로 카카오 장애를 흉내 낸다.
 */
class KakaoApiClientTimeoutTest {

	@Test
	@DisplayName("설정이 없으면 연결 3초, 읽기 5초가 기본값이다")
	void defaultTimeouts() {
		KakaoProperties properties = new Binder(new MapConfigurationPropertySource(Map.of("app.kakao.client-id", "id")))
				.bind("app.kakao", KakaoProperties.class)
				.get();

		assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(3));
		assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(5));
	}

	@Test
	@DisplayName("카카오가 응답하지 않으면 읽기 제한 시간 안에 502 EXTERNAL_SERVICE_ERROR로 끝난다")
	void unresponsiveProviderTimesOut() throws Exception {
		// accept하지 않아도 커널이 연결을 받아 두므로, 요청은 보내지지만 응답은 영영 오지 않는다.
		try (ServerSocket silentServer = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
			String base = "http://127.0.0.1:" + silentServer.getLocalPort();
			KakaoProperties properties = new KakaoProperties("client-id", "", base + "/oauth/token",
					base + "/v2/user/me", Duration.ofSeconds(1), Duration.ofMillis(300));
			KakaoApiClient client = new KakaoApiClient(RestClient.builder(), properties);

			long startedAt = System.nanoTime();
			assertThatThrownBy(() -> client.fetchProviderUserId("code", "http://localhost/callback"))
					.isInstanceOf(BusinessException.class)
					.satisfies(e -> assertThat(((BusinessException) e).errorCode())
							.isEqualTo(ErrorCode.EXTERNAL_SERVICE_ERROR));
			assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofSeconds(4));
		}
	}

}
