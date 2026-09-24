package com.reused.auth.mail;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import com.reused.auth.config.LocalAuthProperties;
import com.reused.auth.config.MailProperties;
import com.reused.common.error.BusinessException;
import com.reused.common.error.ErrorCode;

/**
 * SMTP로 코드 메일을 보낸다. 코드는 메일 본문에만 존재하며 로그에 남기지 않는다(NFR-CRED-006).
 */
@Component
public class SmtpAuthMailSender implements AuthMailSender {

	private static final Logger log = LoggerFactory.getLogger(SmtpAuthMailSender.class);

	private final JavaMailSender mailSender;
	private final MailProperties mailProperties;
	private final LocalAuthProperties authProperties;

	public SmtpAuthMailSender(JavaMailSender mailSender, MailProperties mailProperties,
			LocalAuthProperties authProperties) {
		this.mailSender = mailSender;
		this.mailProperties = mailProperties;
		this.authProperties = authProperties;
	}

	@Override
	public void sendVerificationCode(String email, String code) {
		send(email, "[Re:Used] 이메일 인증 코드", """
				Re:Used 이메일 인증 코드입니다.

				인증 코드: %s

				이 코드는 %d분 동안 유효하며 한 번만 사용할 수 있습니다.
				본인이 요청하지 않았다면 이 메일을 무시해 주세요.
				""".formatted(code, minutes(authProperties.codeTtl())));
	}

	@Override
	public void sendPasswordResetCode(String email, String code) {
		send(email, "[Re:Used] 비밀번호 재설정 코드", """
				Re:Used 비밀번호 재설정 코드입니다.

				재설정 코드: %s

				이 코드는 %d분 동안 유효하며 한 번만 사용할 수 있습니다.
				본인이 요청하지 않았다면 이 메일을 무시해 주세요. 비밀번호는 변경되지 않습니다.
				""".formatted(code, minutes(authProperties.codeTtl())));
	}

	private void send(String to, String subject, String text) {
		SimpleMailMessage message = new SimpleMailMessage();
		message.setFrom(mailProperties.from());
		message.setTo(to);
		message.setSubject(subject);
		message.setText(text);
		try {
			mailSender.send(message);
		}
		catch (MailException e) {
			// 수신 주소는 개인정보이므로 로그에 남기지 않는다(NFR-LOG-003).
			log.error("인증 메일 발송 실패: {}", subject, e);
			throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR,
					"메일을 발송하지 못했습니다. 잠시 후 다시 시도해 주세요.", e);
		}
	}

	private static long minutes(Duration duration) {
		return Math.max(1, duration.toMinutes());
	}

}
