package com.visionary.service;

import com.visionary.entity.User;
import com.visionary.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;

@Slf4j
@Service
@RequiredArgsConstructor
public class PasswordResetService {

    private static final Duration TOKEN_TTL = Duration.ofMinutes(15);
    private static final String KEY_PREFIX = "vt:password-reset:";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final StringRedisTemplate redisTemplate;
    private final PasswordEncoder passwordEncoder;
    private final ObjectProvider<JavaMailSender> mailSenderProvider;

    @Value("${APP_PUBLIC_BASE_URL:http://localhost:5173}")
    private String publicBaseUrl;

    @Value("${MAIL_FROM:no-reply@visionary-tutor.local}")
    private String mailFrom;

    @Value("${spring.mail.host:}")
    private String mailHost;

    public void requestReset(String email) {
        JavaMailSender mailSender = mailSenderProvider.getIfAvailable();
        if (mailSender == null || mailHost == null || mailHost.isBlank()) {
            throw new IllegalStateException("密码找回邮件服务尚未配置，请联系管理员配置 SMTP");
        }

        userRepository.findByEmailIgnoreCase(email.trim()).ifPresent(user -> {
            String token = newToken();
            redisTemplate.opsForValue().set(
                    KEY_PREFIX + tokenDigest(token),
                    String.valueOf(user.getId()),
                    TOKEN_TTL
            );
            sendResetMail(mailSender, user, token);
        });
        // 对存在和不存在的邮箱返回相同结果，避免泄露账户是否存在。
    }

    @Transactional
    public void confirmReset(String token, String newPassword) {
        String key = KEY_PREFIX + tokenDigest(token.trim());
        String userId = redisTemplate.opsForValue().getAndDelete(key);
        if (userId == null) {
            throw new IllegalArgumentException("重置链接无效或已过期，请重新申请");
        }

        User user;
        try {
            user = userRepository.findById(Long.parseLong(userId))
                    .orElseThrow(() -> new IllegalArgumentException("重置链接无效或已过期，请重新申请"));
        } catch (NumberFormatException ex) {
            log.warn("Invalid password reset user id stored in Redis");
            throw new IllegalArgumentException("重置链接无效或已过期，请重新申请");
        }
        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);
    }

    private void sendResetMail(JavaMailSender mailSender, User user, String token) {
        String separator = publicBaseUrl.contains("?") ? "&" : "?";
        String resetUrl = publicBaseUrl.replaceAll("/$", "")
                + "/auth" + separator + "mode=reset&token=" + token;

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(mailFrom);
        message.setTo(user.getEmail());
        message.setSubject("VisionaryTutor 密码重置");
        message.setText("你申请了密码重置。请在 15 分钟内打开以下链接完成操作：\n\n"
                + resetUrl + "\n\n如果不是你本人操作，请忽略此邮件。");
        mailSender.send(message);
    }

    private static String newToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String tokenDigest(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
