package com.reused.auth.mail;

/**
 * 인증 관련 메일 발송. 구현을 인터페이스 뒤에 두어 테스트에서 대역으로 바꾼다.
 *
 * <p>운영 발송 수단은 미결정이며(workload-and-open-items.md), 로컬은 Mailpit 같은 SMTP 대역을 쓴다.
 */
public interface AuthMailSender {

	/**
	 * 이메일 소유 확인 코드.
	 *
	 * @throws com.reused.common.error.BusinessException 발송 실패 시 EXTERNAL_SERVICE_ERROR
	 */
	void sendVerificationCode(String email, String code);

	/**
	 * 비밀번호 재설정 코드.
	 *
	 * @throws com.reused.common.error.BusinessException 발송 실패 시 EXTERNAL_SERVICE_ERROR
	 */
	void sendPasswordResetCode(String email, String code);

}
