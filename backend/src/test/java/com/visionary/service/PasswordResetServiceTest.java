package com.visionary.service;

import com.visionary.entity.User;
import com.visionary.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PasswordResetServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private ObjectProvider<JavaMailSender> mailSenderProvider;
    @Mock private JavaMailSender mailSender;

    private PasswordResetService service;

    @BeforeEach
    void setUp() {
        service = new PasswordResetService(
                userRepository,
                redisTemplate,
                passwordEncoder,
                mailSenderProvider
        );
        ReflectionTestUtils.setField(service, "publicBaseUrl", "https://example.test");
        ReflectionTestUtils.setField(service, "mailFrom", "no-reply@example.test");
        ReflectionTestUtils.setField(service, "mailHost", "smtp.example.test");
    }

    @Test
    void requestResetStoresHashedSingleUseTokenAndSendsMail() {
        User user = new User();
        user.setId(7L);
        user.setEmail("learner@example.test");
        when(mailSenderProvider.getIfAvailable()).thenReturn(mailSender);
        when(userRepository.findByEmailIgnoreCase("learner@example.test")).thenReturn(Optional.of(user));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        service.requestReset("learner@example.test");

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(key.capture(), org.mockito.ArgumentMatchers.eq("7"), org.mockito.ArgumentMatchers.eq(Duration.ofMinutes(15)));
        assertTrue(key.getValue().startsWith("vt:password-reset:"));
        ArgumentCaptor<SimpleMailMessage> message = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(message.capture());
        assertTrue(message.getValue().getText().contains("/auth?mode=reset&token="));
    }

    @Test
    void requestResetDoesNotRevealUnknownEmail() {
        when(mailSenderProvider.getIfAvailable()).thenReturn(mailSender);
        when(userRepository.findByEmailIgnoreCase("missing@example.test")).thenReturn(Optional.empty());

        service.requestReset("missing@example.test");

        verify(redisTemplate, never()).opsForValue();
        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    void confirmResetConsumesTokenAndUpdatesPassword() {
        User user = new User();
        user.setId(9L);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.getAndDelete(anyString())).thenReturn("9");
        when(userRepository.findById(9L)).thenReturn(Optional.of(user));
        when(passwordEncoder.encode("new-password")).thenReturn("encoded-password");

        service.confirmReset("abcdefghijklmnopqrstuvwxyz123456", "new-password");

        assertEquals("encoded-password", user.getPassword());
        verify(userRepository).save(user);
    }

    @Test
    void confirmResetRejectsExpiredToken() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.getAndDelete(anyString())).thenReturn(null);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.confirmReset("abcdefghijklmnopqrstuvwxyz123456", "new-password")
        );
        assertTrue(error.getMessage().contains("无效或已过期"));
    }
}
