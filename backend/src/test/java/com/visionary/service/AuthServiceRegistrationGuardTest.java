package com.visionary.service;

import com.visionary.dto.AuthRequest;
import com.visionary.entity.User;
import com.visionary.repository.UserRepository;
import com.visionary.security.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthServiceRegistrationGuardTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final GuestSessionService guestSessionService = mock(GuestSessionService.class);
    private final JwtUtil jwtUtil = mock(JwtUtil.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final CaptchaService captchaService = mock(CaptchaService.class);
    private final AuthService authService = new AuthService(
            userRepository,
            guestSessionService,
            jwtUtil,
            passwordEncoder,
            captchaService
    );

    @Test
    void registrationWithOptionalEmailRequiresCaptchaAndTermsOnly() {
        AuthRequest.RegisterRequest request = request(true, "learner@example.test");
        User saved = new User();
        saved.setId(11L);
        saved.setUsername("学习者");
        saved.setEmail("learner@example.test");
        when(userRepository.save(org.mockito.ArgumentMatchers.any(User.class))).thenReturn(saved);
        when(passwordEncoder.encode("secure88")).thenReturn("encoded");
        when(jwtUtil.generateToken(11L)).thenReturn("token");

        authService.register(request);

        verify(captchaService).verify("captcha-id", "ABCD2");
    }

    @Test
    void registrationRejectsUncheckedHumanBeforeAnyPersistence() {
        AuthRequest.RegisterRequest request = request(false, "learner@example.test");

        assertThrows(IllegalArgumentException.class, () -> authService.register(request));

        verify(captchaService, never()).verify(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(userRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    private static AuthRequest.RegisterRequest request(boolean termsAccepted, String email) {
        return new AuthRequest.RegisterRequest(
                "学习者",
                "secure88",
                email,
                "学习者",
                "机器学习",
                "captcha-id",
                "ABCD2",
                termsAccepted,
                null
        );
    }
}
