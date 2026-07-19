package com.visionary.exception;

import com.visionary.agent.AgentResponse;
import com.visionary.agent.AgentTaskType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final String TRACE_ID_KEY = "traceId";

    @ExceptionHandler(BizException.class)
    public ResponseEntity<AgentResponse<Void>> handleBizException(BizException ex) {
        log.warn("Business exception: {} - {}", ex.getErrorCode(), ex.getMessage());
        return error(ex.getErrorCode(), ex.getMessage());
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<AgentResponse<Void>> handleResourceNotFound(ResourceNotFoundException ex) {
        return error(VisionaryErrorCode.RESOURCE_NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<AgentResponse<Void>> handleNoResourceFound(NoResourceFoundException ex) {
        return error(VisionaryErrorCode.RESOURCE_NOT_FOUND, "请求的接口不存在");
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<AgentResponse<Void>> handleResponseStatus(ResponseStatusException ex) {
        VisionaryErrorCode code = switch (ex.getStatusCode().value()) {
            case 401 -> VisionaryErrorCode.UNAUTHORIZED;
            case 403 -> VisionaryErrorCode.FORBIDDEN;
            case 404 -> VisionaryErrorCode.RESOURCE_NOT_FOUND;
            default -> VisionaryErrorCode.INVALID_REQUEST;
        };
        String message = ex.getReason() != null ? ex.getReason() : code.getDefaultMessage();
        return error(code, message);
    }

    @ExceptionHandler(AiProviderException.class)
    public ResponseEntity<AgentResponse<Void>> handleAiProviderException(AiProviderException ex) {
        log.error("AI provider exception [{}]: {} (status={})", ex.getProvider(), ex.getMessage(), ex.getStatusCode(), ex);
        return error(
                VisionaryErrorCode.AI_PROVIDER_UNAVAILABLE,
                ex.getProvider() + " 服务暂时不可用: " + ex.getMessage()
        );
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<AgentResponse<Void>> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("Invalid request: {}", ex.getMessage());
        return error(VisionaryErrorCode.INVALID_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<AgentResponse<Void>> handleIllegalState(IllegalStateException ex) {
        log.warn("Service unavailable: {}", ex.getMessage());
        return error(VisionaryErrorCode.AI_PROVIDER_UNAVAILABLE, ex.getMessage());
    }

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            BindException.class,
            ConstraintViolationException.class,
            HttpMessageNotReadableException.class
    })
    public ResponseEntity<AgentResponse<Void>> handleValidationException(Exception ex) {
        log.warn("Request validation failed: {}", ex.getMessage());
        String message = "提交的信息不完整，请检查后重新提交";
        if (ex instanceof MethodArgumentNotValidException methodArgument
                && methodArgument.getBindingResult().getFieldError() != null) {
            message = methodArgument.getBindingResult().getFieldError().getDefaultMessage();
        } else if (ex instanceof BindException bindException
                && bindException.getBindingResult().getFieldError() != null) {
            message = bindException.getBindingResult().getFieldError().getDefaultMessage();
        } else if (ex instanceof ConstraintViolationException constraintViolation
                && !constraintViolation.getConstraintViolations().isEmpty()) {
            message = constraintViolation.getConstraintViolations().iterator().next().getMessage();
        } else if (ex instanceof HttpMessageNotReadableException) {
            message = "请求内容格式不正确，请刷新页面后重试";
        }
        return error(VisionaryErrorCode.INVALID_REQUEST, message);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<AgentResponse<Void>> handleAuthenticationException(AuthenticationException ex) {
        log.warn("Authentication failed: {}", ex.getMessage());
        return error(VisionaryErrorCode.INVALID_REQUEST, "请先登录后再使用该功能");
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<AgentResponse<Void>> handleAccessDenied(AccessDeniedException ex) {
        log.warn("Access denied: {}", ex.getMessage());
        return error(VisionaryErrorCode.INVALID_REQUEST, "当前账号无权访问该功能");
    }

    @ExceptionHandler(java.io.IOException.class)
    public ResponseEntity<AgentResponse<Void>> handleIOException(java.io.IOException ex) {
        log.error("IO exception: {}", ex.getMessage(), ex);
        return error(VisionaryErrorCode.AI_PROVIDER_UNAVAILABLE, "服务暂时不可用，请稍后重试");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<AgentResponse<Void>> handleGenericException(Exception ex) {
        log.error("Unhandled system exception: {}", ex.getMessage(), ex);
        return error(VisionaryErrorCode.INTERNAL_ERROR, "服务暂时出现问题，请稍后重试");
    }

    private ResponseEntity<AgentResponse<Void>> error(VisionaryErrorCode errorCode, String message) {
        return ResponseEntity
                .status(errorCode.getHttpStatus())
                .body(AgentResponse.<Void>builder()
                        .requestId(getCurrentTraceId())
                        .taskType(AgentTaskType.UNKNOWN)
                        .resolvedRoute(AgentTaskType.UNKNOWN)
                        .status(AgentResponse.ResponseStatus.ERROR)
                        .message(message)
                        .errorCode(errorCode.getCode())
                        .timestamp(Instant.now())
                        .build());
    }

    private String getCurrentTraceId() {
        String traceId = MDC.get(TRACE_ID_KEY);
        if (traceId == null || traceId.isBlank()) {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                HttpServletRequest request = attrs.getRequest();
                traceId = request.getHeader("X-Trace-Id");
            }
        }
        return traceId != null ? traceId : "unknown";
    }
}
