package com.visionary.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaptchaServiceTest {

    @Test
    void challengeUsesHeadlessSafeSvgAndIsSingleUseAfterFailedAttempt() {
        CaptchaService service = new CaptchaService();
        CaptchaService.CaptchaChallenge challenge = service.createChallenge();

        assertTrue(challenge.imageDataUrl().startsWith("data:image/svg+xml;base64,"));
        assertTrue(challenge.imageDataUrl().length() > 1_000);
        assertThrows(IllegalArgumentException.class, () -> service.verify(challenge.captchaId(), "WRONG"));
        assertThrows(IllegalArgumentException.class, () -> service.verify(challenge.captchaId(), "WRONG"));
    }
}
