package com.reused.common.error;

/**
 * 모든 오류 응답의 단일 포맷. 스택 트레이스·쿼리·내부 식별자를 담지 않는다(API 명세 §0.3).
 */
public record ErrorResponse(String code, String message) {

	public static ErrorResponse of(ErrorCode code) {
		return new ErrorResponse(code.name(), code.defaultMessage());
	}

	public static ErrorResponse of(ErrorCode code, String message) {
		return new ErrorResponse(code.name(), message);
	}

}
