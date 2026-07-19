package com.visionary.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum VisionaryErrorCode {
    INVALID_REQUEST("VT-4001", HttpStatus.BAD_REQUEST, "请求参数不合法"),
    RESOURCE_NOT_FOUND("VT-4004", HttpStatus.NOT_FOUND, "请求的资源不存在"),
    UNAUTHORIZED("VT-4010", HttpStatus.UNAUTHORIZED, "需要登录"),
    FORBIDDEN("VT-4030", HttpStatus.FORBIDDEN, "无权访问"),
    AGENT_COLLABORATION_FAILED("VT-5002", HttpStatus.SERVICE_UNAVAILABLE, "Agent 协作异常"),
    AI_PROVIDER_UNAVAILABLE("VT-5003", HttpStatus.BAD_GATEWAY, "AI 服务暂时不可用"),
    DEMO_DATA_UNAVAILABLE("VT-5004", HttpStatus.SERVICE_UNAVAILABLE, "演示数据暂时不可用"),
    INTERNAL_ERROR("VT-5000", HttpStatus.INTERNAL_SERVER_ERROR, "系统内部错误");

    private final String code;
    private final HttpStatus httpStatus;
    private final String defaultMessage;

    VisionaryErrorCode(String code, HttpStatus httpStatus, String defaultMessage) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }
}
