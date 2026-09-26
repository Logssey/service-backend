package com.reused.chatbot.llm;

/**
 * LLM 호출 실패. 사용자에게는 모두 502로 나가고, 종류는 외부 연동 기록(FR-LOG-006)과 운영 로그에만 쓴다.
 * 메시지에 공급자 응답 본문·인증정보·사용자 입력을 넣지 않는다.
 */
public class LlmCallException extends RuntimeException {

	public enum Kind {
		/** 설정(인증정보)이 없어 호출하지 않았다 */
		NOT_CONFIGURED,
		/** 공급자가 호출량 초과(429)로 거부했다 */
		RATE_LIMITED,
		/** 공급자 5xx */
		SERVER_ERROR,
		/** 그 밖의 공급자 오류 응답. 400(요청 형식), 401·403(인증정보)이 여기로 온다 */
		CLIENT_ERROR,
		/** 연결 실패·읽기 제한 시간 초과처럼 응답을 받지 못했다 */
		CONNECTION,
		/** 응답을 해석하지 못했다 */
		INVALID_RESPONSE
	}

	private final Kind kind;
	private final Integer httpStatus;

	public LlmCallException(Kind kind, Integer httpStatus, Throwable cause) {
		super("LLM 호출 실패: " + kind, cause);
		this.kind = kind;
		this.httpStatus = httpStatus;
	}

	public Kind kind() {
		return kind;
	}

	/** 공급자가 응답한 HTTP 상태. 응답을 받지 못했으면 null */
	public Integer httpStatus() {
		return httpStatus;
	}

}
