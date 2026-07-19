package com.visionary.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void missingApiRouteReturns404InsteadOfInternalError() {
        var response = handler.handleNoResourceFound(
                new NoResourceFoundException(HttpMethod.POST, "/api/auth/removed")
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getErrorCode()).isEqualTo("VT-4004");
    }

    @Test
    void controllerForbiddenStatusIsPreserved() {
        var response = handler.handleResponseStatus(
                new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问该学习会话")
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getErrorCode()).isEqualTo("VT-4030");
    }
}
