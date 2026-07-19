package com.visionary.exception;

import lombok.Getter;

/**
 * 业务异常 - 用于业务逻辑校验失败。
 * <p>应返回 HTTP 400 Bad Request</p>
 */
@Getter
public class BizException extends RuntimeException {

    private final VisionaryErrorCode errorCode;

    public BizException(String message) {
        super(message);
        this.errorCode = VisionaryErrorCode.INVALID_REQUEST;
    }

    public BizException(VisionaryErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public BizException(String errorCode, String message) {
        super(message);
        this.errorCode = VisionaryErrorCode.INVALID_REQUEST;
    }

    public BizException(String message, Throwable cause) {
        super(message, cause);
        this.errorCode = VisionaryErrorCode.INVALID_REQUEST;
    }
}
