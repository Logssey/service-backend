package com.reused.auth.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.reused.auth.code.AuthCodeStore;
import com.reused.auth.code.CodePurpose;
import com.reused.auth.mail.AuthMailSender;

/**
 * 이메일 소유 확인 코드 발송. 이메일 가입(LOCAL)과 이메일을 입력한 소셜 온보딩이 같은 절차를 쓴다(ADR-019).
 * 발송 제한·코드 키는 이메일이 아니라 identityId 기준이다({@link AuthCodeStore}).
 *
 * <p>로그에 수신 주소를 남기지 않는다(NFR-LOG-003).
 */
@Component
class EmailVerificationMailer {

	private static final Logger log = LoggerFactory.getLogger(EmailVerificationMailer.class);

	private final AuthCodeStore codeStore;
	private final AuthMailSender mailSender;

	EmailVerificationMailer(AuthCodeStore codeStore, AuthMailSender mailSender) {
		this.codeStore = codeStore;
		this.mailSender = mailSender;
	}

	/**
	 * 발송 제한을 확인·기록하고 새 코드를 발급해 보낸다. 이전 코드와 시도 횟수는 폐기된다.
	 *
	 * @throws com.reused.common.error.BusinessException RATE_LIMITED 발송 제한, EXTERNAL_SERVICE_ERROR 발송 실패
	 */
	void send(Long identityId, String email) {
		codeStore.recordSend(identityId);
		String code = codeStore.issue(CodePurpose.VERIFY, identityId);
		mailSender.sendVerificationCode(email, code);
	}

	/**
	 * 가입 직후 1회 자동 발송. 실패해도 가입을 되돌리지 않는다 — 사용자가 재발송으로 복구할 수 있다.
	 */
	void sendQuietly(Long identityId, String email) {
		try {
			send(identityId, email);
		}
		catch (RuntimeException e) {
			log.warn("가입 직후 소유 확인 메일 발송 실패. 재발송으로 복구 가능. identityId={}", identityId, e);
		}
	}

}
