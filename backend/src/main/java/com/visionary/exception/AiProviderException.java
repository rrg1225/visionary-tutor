package com.visionary.exception;

import lombok.Getter;

/**
 * AI 服务提供商异常 - 用于第三方 API 调用失败。
 * <p>应返回 HTTP 502 Bad Gateway 或 503 Service Unavailable</p>
 */
@Getter
public class AiProviderException extends RuntimeException {

    private final String provider;
    private final int statusCode;

    public AiProviderException(String provider, String message) {
        super(message);
        this.provider = provider;
        this.statusCode = 0;
    }

    public AiProviderException(String provider, int statusCode, String message) {
        super(message);
        this.provider = provider;
        this.statusCode = statusCode;
    }

    public AiProviderException(String provider, String message, Throwable cause) {
        super(message, cause);
        this.provider = provider;
        this.statusCode = 0;
    }
}
