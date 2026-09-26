package com.reused.common.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 모든 예외를 {@link ErrorResponse}로 바꾼다. 응답에 내부 타입 이름·스택·쿼리를 싣지 않는다(API 명세 §0.5).
 *
 * <p>오류 코드표에 405·415가 없으므로 메서드·미디어 타입 불일치는 400 INVALID_INPUT으로 낸다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	private static final String TYPE_MISMATCH_CODE = "typeMismatch";
	private static final String TYPE_MISMATCH_MESSAGE = ": 값의 형식이 올바르지 않습니다.";
	private static final String CONFLICT_RETRY_MESSAGE = "다른 요청과 충돌했습니다. 다시 시도해 주세요.";

	@ExceptionHandler(BusinessException.class)
	public ResponseEntity<ErrorResponse> handleBusiness(BusinessException e) {
		return ResponseEntity.status(e.errorCode().status())
				.body(ErrorResponse.of(e.errorCode(), e.getMessage()));
	}

	/**
	 * Bean Validation 실패와 {@code @ModelAttribute} 바인딩 실패. 어떤 필드가 왜 틀렸는지는 알려주되 내부 구조는 노출하지 않는다.
	 * 타입 변환 실패(typeMismatch)의 기본 메시지에는 변환 대상 타입 이름이 들어 있어 고정 문구로 바꾼다.
	 */
	@ExceptionHandler({ MethodArgumentNotValidException.class, BindException.class })
	public ResponseEntity<ErrorResponse> handleValidation(BindException e) {
		String message = e.getBindingResult().getFieldErrors().stream()
				.findFirst()
				.map(GlobalExceptionHandler::fieldMessage)
				.orElse(ErrorCode.INVALID_INPUT.defaultMessage());
		return invalidInput(message);
	}

	/**
	 * {@code @PathVariable}·{@code @RequestParam} 타입 변환 실패. 예: {@code /notifications/abc/read}.
	 */
	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
		return invalidInput(e.getName() + TYPE_MISMATCH_MESSAGE);
	}

	@ExceptionHandler(MissingServletRequestParameterException.class)
	public ResponseEntity<ErrorResponse> handleMissingParameter(MissingServletRequestParameterException e) {
		return invalidInput(e.getParameterName() + ": 필수 값입니다.");
	}

	/**
	 * {@code @RequestParam @Max(100) Integer size}처럼 메서드 파라미터에 직접 단 제약의 위반.
	 */
	@ExceptionHandler(HandlerMethodValidationException.class)
	public ResponseEntity<ErrorResponse> handleMethodValidation(HandlerMethodValidationException e) {
		return invalidInput(ErrorCode.INVALID_INPUT.defaultMessage());
	}

	/**
	 * 본문을 읽을 수 없는 요청. 깨진 JSON, 타입 불일치, 빠진 boolean 필드가 여기로 온다.
	 * Jackson 3은 빠진 primitive를 기본값으로 채우지 않고 실패시킨다(FAIL_ON_NULL_FOR_PRIMITIVES).
	 * 파서 메시지에는 내부 타입 이름이 들어 있어 응답에 싣지 않는다.
	 */
	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ErrorResponse> handleNotReadable(HttpMessageNotReadableException e) {
		log.debug("요청 본문을 읽을 수 없음", e);
		return invalidInput("요청 본문 형식이 올바르지 않습니다.");
	}

	/**
	 * 매핑이 없는 경로. 인증이 필요한 경로라면 보안 필터가 먼저 401을 낸다.
	 */
	@ExceptionHandler(NoResourceFoundException.class)
	public ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException e) {
		return ResponseEntity.status(ErrorCode.NOT_FOUND.status())
				.body(ErrorResponse.of(ErrorCode.NOT_FOUND));
	}

	@ExceptionHandler(HttpRequestMethodNotSupportedException.class)
	public ResponseEntity<ErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
		return invalidInput("지원하지 않는 요청 메서드입니다.");
	}

	@ExceptionHandler(HttpMediaTypeNotSupportedException.class)
	public ResponseEntity<ErrorResponse> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException e) {
		return invalidInput("지원하지 않는 Content-Type입니다.");
	}

	/**
	 * 낙관적 락(@Version) 충돌과 비관적 락 획득 실패. 요청 자체는 올바르므로 다시 시도하면 된다.
	 * {@link ObjectOptimisticLockingFailureException}은 {@link OptimisticLockingFailureException}의 하위 타입이다.
	 */
	@ExceptionHandler({ OptimisticLockingFailureException.class, PessimisticLockingFailureException.class })
	public ResponseEntity<ErrorResponse> handleLockingFailure(RuntimeException e) {
		log.info("동시 변경 충돌: {}", e.getClass().getSimpleName());
		return ResponseEntity.status(ErrorCode.CONFLICT.status())
				.body(ErrorResponse.of(ErrorCode.CONFLICT, CONFLICT_RETRY_MESSAGE));
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
		log.error("처리되지 않은 예외", e);
		return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.status())
				.body(ErrorResponse.of(ErrorCode.INTERNAL_ERROR));
	}

	private static String fieldMessage(FieldError error) {
		if (TYPE_MISMATCH_CODE.equals(error.getCode())) {
			return error.getField() + TYPE_MISMATCH_MESSAGE;
		}
		return error.getField() + ": " + error.getDefaultMessage();
	}

	private static ResponseEntity<ErrorResponse> invalidInput(String message) {
		return ResponseEntity.status(ErrorCode.INVALID_INPUT.status())
				.body(ErrorResponse.of(ErrorCode.INVALID_INPUT, message));
	}

}
