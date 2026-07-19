package com.visionary.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthRequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void loginAcceptsTwoCharacterChineseUsername() {
        AuthRequest.LoginRequest request = new AuthRequest.LoginRequest(
                "小帅", "123456!@", null
        );

        assertTrue(validator.validate(request).isEmpty());
    }

    @Test
    void registrationAcceptsChineseUsernameAndStrongPassword() {
        AuthRequest.RegisterRequest request = new AuthRequest.RegisterRequest(
                "小智_01", "secure88", "learner@example.com", "小智", "自然语言处理",
                "captcha-id", "ABCD2", true, null
        );

        assertTrue(validator.validate(request).isEmpty());
    }

    @Test
    void registrationRejectsLongUsernameWeakPasswordAndMissingTermsAgreement() {
        AuthRequest.RegisterRequest request = new AuthRequest.RegisterRequest(
                "username_is_too_long", "123456", null, null, null,
                "captcha-id", "ABCD2", false, null
        );

        var messages = validator.validate(request).stream()
                .map(violation -> violation.getMessage())
                .toList();
        assertFalse(messages.isEmpty());
        assertTrue(messages.stream().anyMatch(message -> message.contains("2-12")));
        assertTrue(messages.stream().anyMatch(message -> message.contains("8-128")));
        assertTrue(messages.stream().anyMatch(message -> message.contains("用户协议")));
    }

    @Test
    void registrationAcceptsNumericQqEmailWithoutAnyVerificationCode() {
        AuthRequest.RegisterRequest request = new AuthRequest.RegisterRequest(
                "小智_02", "secure88", "1234567890@qq.com", null, null,
                "captcha-id", "ABCD2", true, null
        );

        assertTrue(validator.validate(request).isEmpty());
    }

    @Test
    void registrationRejectsNonNumericQqEmailButKeepsOtherEmailProvidersAvailable() {
        AuthRequest.RegisterRequest invalidQq = new AuthRequest.RegisterRequest(
                "小智_03", "secure88", "nickname@qq.com", null, null,
                "captcha-id", "ABCD2", true, null
        );
        AuthRequest.RegisterRequest otherProvider = new AuthRequest.RegisterRequest(
                "小智_04", "secure88", "nickname@example.com", null, null,
                "captcha-id", "ABCD2", true, null
        );

        var messages = validator.validate(invalidQq).stream()
                .map(violation -> violation.getMessage())
                .toList();
        assertTrue(messages.stream().anyMatch(message -> message.contains("QQ邮箱格式不正确")));
        assertTrue(validator.validate(otherProvider).isEmpty());
    }
}
