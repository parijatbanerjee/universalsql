package com.ema.usql.shared;

/**
 * Application exception that wraps an ErrorCode.
 * Always use this (never raw RuntimeException) when crossing module boundaries.
 */
public class UsqlException extends RuntimeException {

    private final ErrorCode errorCode;

    public UsqlException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public UsqlException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
