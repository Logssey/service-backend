package com.reused.auth.service;

import java.util.Locale;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * DB 제약 위반에서 제약 이름만 꺼낸다. 서버 DETAIL에는 행 값(이메일 등)이 담기므로 원 예외를 로그·응답에 싣지 않고
 * 이름으로만 판정한다(NFR-LOG-003). DETAIL은 드라이버 설정(logServerErrorDetail=false)으로 예외 메시지에서도 빠지며,
 * 제약 이름은 주 메시지에 남는다.
 */
final class ConstraintNames {

	private ConstraintNames() {
	}

	/**
	 * @return 위반한 제약 이름(소문자). Hibernate가 이름을 뽑지 못했으면 null
	 */
	static String of(DataIntegrityViolationException e) {
		for (Throwable cause = e; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
			if (cause instanceof ConstraintViolationException violation && violation.getConstraintName() != null) {
				return violation.getConstraintName().toLowerCase(Locale.ROOT);
			}
		}
		return null;
	}

	/**
	 * Hibernate는 영어 서버 메시지의 틀({@code constraint "..."})로 이름을 뽑는다. 서버 메시지가 다른 언어면 이름이 비므로
	 * 메시지에 따옴표로 둘러싼 이름이 있는지로 대신 판정한다. 메시지는 판정에만 쓰고 밖으로 내보내지 않는다.
	 */
	static boolean matches(DataIntegrityViolationException e, String constraintName) {
		String name = of(e);
		if (name != null) {
			return name.equalsIgnoreCase(constraintName);
		}
		String message = e.getMostSpecificCause().getMessage();
		return message != null && message.contains("\"" + constraintName + "\"");
	}

}
