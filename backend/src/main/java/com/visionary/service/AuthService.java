package com.visionary.service;

import com.visionary.dto.AuthRequest;
import com.visionary.dto.AuthResponse;
import com.visionary.entity.User;
import com.visionary.repository.UserRepository;
import com.visionary.security.JwtUtil;
import com.visionary.service.GuestSessionService.MigrationResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Auth Service - 认证核心业务逻辑
 * 
 * 核心职责：
 * 1. 游客会话创建
 * 2. 用户注册（支持数据迁移）
 * 3. 用户登录（支持数据迁移）
 * 4. Token 刷新
 * 
 * 渐进式注册（Progressive Profiling）实现：
 * - 注册/登录时，如果提供了 guestId，自动触发数据迁移
 * - 迁移过程在事务中完成，确保数据一致性
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final GuestSessionService guestSessionService;
    private final JwtUtil jwtUtil;
    private final PasswordEncoder passwordEncoder;
    private final CaptchaService captchaService;

    /**
     * Token 默认有效期（秒）
     */
    private static final long TOKEN_EXPIRES_IN_SECONDS = 86400; // 24小时

    /**
     * 游客 Token 有效期（秒）
     */
    private static final long GUEST_TOKEN_EXPIRES_IN_SECONDS = 604800; // 7天

    /**
     * 创建游客会话
     * 
     * @param request 创建请求
     * @return 游客会话响应
     */
    public AuthResponse.GuestSuccess createGuestSession(AuthRequest.GuestCreateRequest request) {
        GuestSessionService.GuestSessionResult result = guestSessionService.createGuestSession(
                request.deviceFingerprint(),
                request.contextJson(),
                null  // IP地址在 Controller 层获取
        );

        var quota = result.chatQuota();
        return new AuthResponse.GuestSuccess(
                result.token(),
                "Bearer",
                GUEST_TOKEN_EXPIRES_IN_SECONDS,
                result.guestId(),
                result.expiresAt(),
                new AuthResponse.GuestChatQuotaInfo(
                        quota.usedTurns(),
                        quota.maxTurns(),
                        quota.remainingTurns(),
                        quota.sessionTtlSeconds()
                )
        );
    }

    /**
     * 查询游客对话配额（Redis）。
     */
    public AuthResponse.GuestChatQuotaInfo getGuestChatQuota(String guestId) {
        var quota = guestSessionService.getChatQuota(guestId);
        return new AuthResponse.GuestChatQuotaInfo(
                quota.usedTurns(),
                quota.maxTurns(),
                quota.remainingTurns(),
                quota.sessionTtlSeconds()
        );
    }

    public boolean updateGuestContext(String guestId, String contextJson) {
        return guestSessionService.updateContext(guestId, contextJson);
    }

    /**
     * 用户注册
     * 
     * @param request 注册请求
     * @return 注册成功响应（包含 token 和数据迁移信息）
     */
    @Transactional
    public AuthResponse.Success register(AuthRequest.RegisterRequest request) {
        if (!Boolean.TRUE.equals(request.termsAccepted())) {
            throw new IllegalArgumentException("请阅读并同意用户协议与隐私说明");
        }
        captchaService.verify(request.captchaId(), request.captchaAnswer());

        // 1. 验证用户名唯一性
        if (userRepository.existsByUsername(request.username())) {
            throw new IllegalArgumentException("用户名已存在");
        }

        // 2. 验证邮箱唯一性（如果提供了邮箱）
        if (StringUtils.hasText(request.email()) && userRepository.existsByEmail(request.email())) {
            throw new IllegalArgumentException("邮箱已被注册");
        }
        // 3. 创建用户
        User user = new User();
        user.setUsername(request.username());
        user.setPassword(passwordEncoder.encode(request.password()));
        user.setEmail(request.email());
        user.setDisplayName(StringUtils.hasText(request.displayName()) 
                ? request.displayName() 
                : request.username());
        user.setLearningGoal(request.learningPreference());
        user.setStatus(User.UserStatus.ACTIVE);
        // 记录游客ID（用于审计）
        if (StringUtils.hasText(request.guestId())) {
            user.setPreviousGuestId(request.guestId());
        }

        User savedUser = userRepository.save(user);
        log.info("User registered: userId={}, username={}", savedUser.getId(), savedUser.getUsername());

        // 4. 数据迁移（如果提供了 guestId）
        AuthResponse.MigrationInfo migrationInfo = null;
        if (StringUtils.hasText(request.guestId())) {
            migrationInfo = performMigration(request.guestId(), savedUser.getId());
        }

        // 5. 生成 Token
        String token = jwtUtil.generateToken(savedUser.getId());

        return new AuthResponse.Success(
                token,
                "Bearer",
                TOKEN_EXPIRES_IN_SECONDS,
                false,  // isGuest = false
                AuthResponse.UserInfo.fromEntity(savedUser),
                migrationInfo
        );
    }

    /**
     * 用户登录
     * 
     * @param request 登录请求
     * @return 登录成功响应（包含 token 和数据迁移信息）
     */
    @Transactional
    public AuthResponse.Success login(AuthRequest.LoginRequest request) {
        // 1. 查找用户
        User user = userRepository.findByUsername(request.username())
                .orElseThrow(() -> new IllegalArgumentException("用户名或密码错误"));

        // 2. 验证密码
        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new IllegalArgumentException("用户名或密码错误");
        }

        // 3. 检查用户状态
        if (user.getStatus() != User.UserStatus.ACTIVE) {
            throw new IllegalStateException("账户已被禁用");
        }

        log.info("User logged in: userId={}, username={}", user.getId(), user.getUsername());

        // 4. 数据迁移（如果提供了 guestId）
        AuthResponse.MigrationInfo migrationInfo = null;
        if (StringUtils.hasText(request.guestId())) {
            migrationInfo = performMigration(request.guestId(), user.getId());
        }

        // 5. 生成 Token
        String token = jwtUtil.generateToken(user.getId());

        return new AuthResponse.Success(
                token,
                "Bearer",
                TOKEN_EXPIRES_IN_SECONDS,
                false,  // isGuest = false
                AuthResponse.UserInfo.fromEntity(user),
                migrationInfo
        );
    }

    /**
     * 刷新 Token
     * 
     * @param oldToken 旧 Token
     * @return 新的 Token（类型不变：游客还是游客，正式用户还是正式用户）
     */
    public String refreshToken(String oldToken) {
        if (!jwtUtil.validateToken(oldToken)) {
            throw new IllegalArgumentException("Invalid token");
        }

        String newToken = jwtUtil.refreshToken(oldToken);
        log.debug("Token refreshed");
        
        return newToken;
    }

    /**
     * 获取当前用户信息
     * 
     * @param userId 用户ID
     * @return 用户信息
     */
    public AuthResponse.UserInfo getCurrentUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        
        return AuthResponse.UserInfo.fromEntity(user);
    }

    /**
     * 验证并延长游客会话
     * 
     * @param guestId 游客ID
     * @return 是否成功延长
     */
    public boolean validateAndExtendGuestSession(String guestId) {
        Optional<com.visionary.entity.GuestSession> session = 
                guestSessionService.validateGuestSession(guestId);
        
        if (session.isEmpty()) {
            return false;
        }

        // 自动延长会话有效期
        return guestSessionService.extendSession(guestId);
    }

    /**
     * 执行数据迁移
     * 
     * @param guestId 游客ID
     * @param userId 正式用户ID
     * @return 迁移信息
     */
    private AuthResponse.MigrationInfo performMigration(String guestId, Long userId) {
        try {
            MigrationResult result = guestSessionService.migrateToUser(guestId, userId);

            if (!result.success()) {
                log.warn("Migration failed: {}", result.message());
                return null;
            }

            AuthResponse.MigrationInfo info = new AuthResponse.MigrationInfo(
                    true,
                    result.guestId(),
                    result.migratedSessionsCount(),
                    result.migratedReportsCount()
            );

            log.info("Migration completed: guestId={}, userId={}, sessions={}, reports={}",
                    guestId, userId, result.migratedSessionsCount(), result.migratedReportsCount());

            return info;

        } catch (Exception e) {
            log.error("Migration error: guestId={}, userId={}", guestId, userId, e);
            // 迁移失败不影响登录/注册成功，记录错误即可
            return null;
        }
    }

    /**
     * 数据迁移信息
     */
    public record MigrationInfo(
            boolean migrated,
            String fromGuestId,
            Integer migratedSessionsCount,
            Integer migratedReportsCount
    ) {}
}
