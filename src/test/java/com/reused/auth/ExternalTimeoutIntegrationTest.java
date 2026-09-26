package com.reused.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URL;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.UrlResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.reused.TestcontainersConfiguration;
import com.reused.auth.client.OAuthProviderClient;
import com.reused.auth.config.KakaoProperties;
import com.reused.auth.mail.AuthMailSender;

/**
 * 외부 호출 제한 시간 설정이 실제 빈에 반영되는지 확인한다(NFR-EXT-002).
 * 외부 대역 구성은 인증 통합 테스트와 같게 두어 컨텍스트를 함께 쓴다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ExternalTimeoutIntegrationTest {

	private static final String PROPERTIES_FILE = "application.properties";

	@Autowired
	private JavaMailSender javaMailSender;

	@Autowired
	private KakaoProperties kakaoProperties;

	@MockitoBean
	private AuthMailSender mailSender;

	@MockitoBean
	private OAuthProviderClient kakaoOAuthClient;

	@Test
	@DisplayName("SMTP 연결·읽기·쓰기 제한 시간이 5초로 설정된다")
	void smtpTimeouts() {
		var properties = ((JavaMailSenderImpl) javaMailSender).getJavaMailProperties();

		assertThat(properties.getProperty("mail.smtp.connectiontimeout")).isEqualTo("5000");
		assertThat(properties.getProperty("mail.smtp.timeout")).isEqualTo("5000");
		assertThat(properties.getProperty("mail.smtp.writetimeout")).isEqualTo("5000");
	}

	/**
	 * 위 테스트는 테스트용 application.properties의 값을 본다. 그 파일이 클래스패스 앞에 있어 운영 파일을 가리고,
	 * SMTP 제한 시간은 코드 기본값이 없다(카카오는 {@code @DefaultValue}가 있다). 그래서 운영 파일을 따로 읽는다.
	 * 키 이름이 JavaMailSender에 반영되는지는 같은 키를 쓰는 위 테스트가 보여 준다.
	 */
	@Test
	@DisplayName("운영 설정 파일에도 SMTP 연결·읽기·쓰기 제한 시간 5초가 있다")
	void productionSmtpTimeouts() throws IOException {
		Properties production = PropertiesLoaderUtils.loadProperties(new UrlResource(productionPropertiesUrl()));

		assertThat(production.getProperty("spring.mail.properties.mail.smtp.connectiontimeout")).isEqualTo("5000");
		assertThat(production.getProperty("spring.mail.properties.mail.smtp.timeout")).isEqualTo("5000");
		assertThat(production.getProperty("spring.mail.properties.mail.smtp.writetimeout")).isEqualTo("5000");
	}

	@Test
	@DisplayName("카카오 연결 3초, 읽기 5초가 설정된다")
	void kakaoTimeouts() {
		assertThat(kakaoProperties.connectTimeout()).isEqualTo(Duration.ofSeconds(3));
		assertThat(kakaoProperties.readTimeout()).isEqualTo(Duration.ofSeconds(5));
	}

	/**
	 * 클래스패스의 application.properties 중 테스트 리소스(맨 앞, {@code getResource}가 돌려주는 것)가 아닌
	 * 파일 하나가 main 리소스다. 의존성 jar 안의 같은 이름 파일은 제외한다.
	 */
	private static URL productionPropertiesUrl() throws IOException {
		ClassLoader classLoader = ExternalTimeoutIntegrationTest.class.getClassLoader();
		String shadowing = classLoader.getResource(PROPERTIES_FILE).toString();
		List<URL> production = Collections.list(classLoader.getResources(PROPERTIES_FILE)).stream()
				.filter(url -> "file".equals(url.getProtocol()) && !url.toString().equals(shadowing))
				.toList();
		assertThat(production).as("main 리소스의 " + PROPERTIES_FILE).hasSize(1);
		return production.get(0);
	}

}
