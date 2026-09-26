package com.reused.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.Map;

import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.reused.auth.config.LocalAuthProperties;
import com.reused.auth.config.MailProperties;
import com.reused.auth.mail.SmtpAuthMailSender;
import com.reused.common.error.BusinessException;
import com.reused.common.error.ErrorCode;

/**
 * 메일 발송 실패 로그에 수신 주소가 새지 않는지(NFR-LOG-003). SMTP 서버의 수신 거부 응답은 흔히 주소를 되풀이하고,
 * 그 응답이 중첩 예외의 메시지와 {@link MailSendException}의 메시지에 그대로 들어간다.
 */
class SmtpAuthMailSenderTest {

	private static final String RECIPIENT = "victim@example.com";
	private static final String CODE = "123456";

	private final JavaMailSender javaMailSender = mock(JavaMailSender.class);
	private final SmtpAuthMailSender sender = new SmtpAuthMailSender(javaMailSender,
			new MailProperties("no-reply@reused.local"),
			new LocalAuthProperties(Duration.ofMinutes(10), 5, Duration.ofSeconds(60), 5, Duration.ofHours(1), 5,
					Duration.ofMinutes(10)));

	private final Logger logger = (Logger) LoggerFactory.getLogger(SmtpAuthMailSender.class);
	private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

	@BeforeEach
	void attachAppender() {
		appender.start();
		logger.addAppender(appender);
	}

	@AfterEach
	void detachAppender() {
		logger.detachAppender(appender);
		appender.stop();
	}

	@Test
	@DisplayName("수신 거부로 실패하면 502이고, 로그와 던지는 예외에는 수신 주소·코드가 없고 예외 종류만 남는다")
	void rejectedRecipientIsNotLogged() {
		MessagingException rejected = new SendFailedException("Invalid Addresses",
				new MessagingException("550 5.1.1 <" + RECIPIENT + ">: Recipient address rejected"));
		MailSendException failure = new MailSendException(Map.<Object, Exception>of(new SimpleMailMessage(), rejected));
		// 전제: 원 예외를 그대로 로그에 넘기면 주소가 찍힌다.
		assertThat(failure.getMessage()).contains(RECIPIENT);
		willThrow(failure).given(javaMailSender).send(any(SimpleMailMessage.class));

		assertThatThrownBy(() -> sender.sendVerificationCode(RECIPIENT, CODE))
				.isInstanceOfSatisfying(BusinessException.class, e -> {
					assertThat(e.errorCode()).isEqualTo(ErrorCode.EXTERNAL_SERVICE_ERROR);
					// 바깥에서 이 예외를 스택 트레이스와 함께 남겨도(가입 직후 발송 등) 원 예외가 따라가지 않는다.
					assertThat(e.getCause()).isNull();
					assertThat(e.getMessage()).doesNotContain(RECIPIENT);
				});

		assertThat(appender.list).singleElement().satisfies(event -> {
			assertThat(event.getThrowableProxy()).isNull();
			assertThat(event.getFormattedMessage())
					.doesNotContain(RECIPIENT)
					.doesNotContain(CODE)
					.contains("[Re:Used] 이메일 인증 코드")
					.contains("MailSendException[SendFailedException<-MessagingException]");
		});
	}

}
