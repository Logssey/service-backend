package com.reused.auth.mail;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.StringJoiner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.MailSendException;
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
	private static final int MAX_CAUSE_DEPTH = 5;

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
			// 수신 주소는 개인정보이므로 로그에 남기지 않는다(NFR-LOG-003). 예외 메시지·스택 트레이스·cause에도
			// 주소가 들어 있을 수 있어 종류만 남기고, 던지는 예외에도 원 예외를 싣지 않는다(바깥 로그로 새지 않게).
			log.error("인증 메일 발송 실패. subject={}, exception={}", subject, describe(e));
			throw new BusinessException(ErrorCode.EXTERNAL_SERVICE_ERROR,
					"메일을 발송하지 못했습니다. 잠시 후 다시 시도해 주세요.");
		}
	}

	/**
	 * 예외 종류만 이어 붙인다. 예: {@code MailSendException[SendFailedException<-SMTPAddressFailedException]}.
	 *
	 * <p>메시지는 넣지 않는다. 수신 거부 시 SMTP 서버 응답이 중첩 예외의 메시지에 그대로 담기고, 서버는 흔히 수신 주소를
	 * 되풀이한다("550 5.1.1 &lt;user@example.com&gt;: Recipient address rejected"). {@link MailSendException}은
	 * 이 메시지를 자기 메시지에도 붙인다. 응답 코드를 꺼내는 SMTP 구현 클래스는 컴파일 의존성이 아니라 쓰지 않는다.
	 */
	static String describe(MailException failure) {
		List<Throwable> nested = new ArrayList<>();
		if (failure.getCause() != null) {
			nested.add(failure.getCause());
		}
		if (failure instanceof MailSendException sendFailure) {
			nested.addAll(Arrays.asList(sendFailure.getMessageExceptions()));
		}

		StringBuilder description = new StringBuilder(failure.getClass().getSimpleName());
		if (nested.isEmpty()) {
			return description.toString();
		}
		StringJoiner branches = new StringJoiner(", ", "[", "]");
		for (Throwable cause : nested) {
			branches.add(causeChain(cause));
		}
		return description.append(branches).toString();
	}

	/** 순환 참조에 대비해 깊이를 제한한다. */
	private static String causeChain(Throwable cause) {
		StringJoiner chain = new StringJoiner("<-");
		Throwable current = cause;
		for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
			chain.add(current.getClass().getSimpleName());
			current = current.getCause() == current ? null : current.getCause();
		}
		return chain.toString();
	}

	private static long minutes(Duration duration) {
		return Math.max(1, duration.toMinutes());
	}

}
