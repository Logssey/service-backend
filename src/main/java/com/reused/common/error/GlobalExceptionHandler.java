package com.reused.common.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@ExceptionHandler(BusinessException.class)
	public ResponseEntity<ErrorResponse> handleBusiness(BusinessException e) {
		return ResponseEntity.status(e.errorCode().status())
				.body(ErrorResponse.of(e.errorCode(), e.getMessage()));
	}

	/**
	 * Bean Validation 실패. 어떤 필드가 왜 틀렸는지는 알려주되 내부 구조는 노출하지 않는다.
	 */
	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
		String message = e.getBindingResult().getFieldErrors().stream()
				.findFirst()
				.map(error -> error.getField() + ": " + error.getDefaultMessage())
				.orElse(ErrorCode.INVALID_INPUT.defaultMessage());
		return ResponseEntity.status(ErrorCode.INVALID_INPUT.status())
				.body(ErrorResponse.of(ErrorCode.INVALID_INPUT, message));
	}

	/**
	 * 본문을 읽을 수 없는 요청. 깨진 JSON, 타입 불일치, 빠진 boolean 필드가 여기로 온다.
	 * Jackson 3은 빠진 primitive를 기본값으로 채우지 않고 실패시킨다(FAIL_ON_NULL_FOR_PRIMITIVES).
	 * 파서 메시지에는 내부 타입 이름이 들어 있어 응답에 싣지 않는다.
	 */
	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ErrorResponse> handleNotReadable(HttpMessageNotReadableException e) {
		log.debug("요청 본문을 읽을 수 없음", e);
		return ResponseEntity.status(ErrorCode.INVALID_INPUT.status())
				.body(ErrorResponse.of(ErrorCode.INVALID_INPUT, "요청 본문 형식이 올바르지 않습니다."));
	}

	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public ResponseEntity<ErrorResponse> handleQueryTypeMismatch(MethodArgumentTypeMismatchException e) {
		return ResponseEntity.status(ErrorCode.INVALID_INPUT.status())
				.body(ErrorResponse.of(ErrorCode.INVALID_INPUT));
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
		log.error("처리되지 않은 예외", e);
		return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.status())
				.body(ErrorResponse.of(ErrorCode.INTERNAL_ERROR));
	}

}
