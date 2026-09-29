package com.reused.auth.token;

import com.reused.common.error.BusinessException;
import com.reused.common.error.ErrorCode;

public class InvalidTokenException extends BusinessException {

	public InvalidTokenException(String message) {
		super(ErrorCode.UNAUTHENTICATED, message);
	}

	public InvalidTokenException(String message, Throwable cause) {
		super(ErrorCode.UNAUTHENTICATED, message, cause);
	}

}
