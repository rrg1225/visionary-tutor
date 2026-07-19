package com.visionary.controller;

import com.visionary.dto.AuthRequest;
import com.visionary.dto.AuthResponse;
import com.visionary.security.CustomUserDetails;
import com.visionary.security.JwtUtil;
import com.visionary.service.AuthService;
import com.visionary.service.CaptchaService;
import com.visionary.service.PasswordResetService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

/**
 * 认证控制器 - 处理登录、注册、游客会话等认证相关请求
 * 
 * REST API 端点：
 * - POST /api/auth/guest         - 创建游客会话
 * - POST /api/auth/register     - 用户注册（支持数据迁移）
 * - POST /api/auth/login        - 用户登录（支持数据迁移）
 * - POST /api/auth/refresh      - 刷新 Token
 * - POST /api/auth/logout       - 用户登出
 * 
 * 渐进式注册（Progressive Profiling）实现：
 * 注册和登录接口接受可选的 guestId 参数，用于触发数据迁移。
 */
@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final JwtUtil jwtUtil;
    private final CaptchaService captchaService;
    private final PasswordResetService passwordResetService;

    @GetMapping("/captcha")
    public CaptchaService.CaptchaChallenge createCaptcha(HttpServletRequest request) {
        return captchaService.createChallenge(request.getRemoteAddr());
    }

    /**
     * 创建游客会话
     * 
     * 首页加载时调用，返回游客 JWT Token 和 guestId
     * 
     * @param request 创建请求（可选设备指纹和上下文）
     * @param servletRequest HTTP请求（用于获取IP地址）
     * @return 游客会话响应
     */
    @PostMapping("/guest")
    public ResponseEntity<AuthResponse.GuestSuccess> createGuestSession(
            @RequestBody(required = false) AuthRequest.GuestCreateRequest request,
            HttpServletRequest servletRequest) {
        
        AuthRequest.GuestCreateRequest actualRequest = request != null ? request 
                : new AuthRequest.GuestCreateRequest(null, null);
        
        log.info("Creating guest session, deviceFingerprint={}", actualRequest.deviceFingerprint());
        
        AuthResponse.GuestSuccess response = authService.createGuestSession(actualRequest);
        
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * 查询游客免费对话剩余次数（Redis，需携带游客 JWT）。
     */
    @GetMapping("/guest/quota")
    public ResponseEntity<AuthResponse.GuestChatQuotaInfo> getGuestChatQuota() {
        String guestId = resolveAuthenticatedGuestId();
        if (guestId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(authService.getGuestChatQuota(guestId));
    }

    @PutMapping("/guest/context")
    public ResponseEntity<GuestSnapshotResponse> saveGuestSnapshot(
            @Valid @RequestBody AuthRequest.GuestSnapshotRequest request) {
        String guestId = resolveAuthenticatedGuestId();
        if (guestId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new GuestSnapshotResponse(false));
        }
        return ResponseEntity.ok(new GuestSnapshotResponse(
                authService.updateGuestContext(guestId, request.contextJson())
        ));
    }

    /**
     * 用户注册
     * 
     * 注册新用户，如果提供了 guestId，会自动迁移游客的会话数据
     * 
     * @param request 注册请求（包含用户名、密码、邮箱、可选的 guestId）
     * @return 注册成功响应（包含 Token 和数据迁移信息）
     */
    @PostMapping("/register")
    public ResponseEntity<AuthResponse.Success> register(
            @Valid @RequestBody AuthRequest.RegisterRequest request) {
        
        log.info("User registration attempt: username={}, guestId={}", 
                request.username(), request.guestId());
        
        try {
            AuthResponse.Success response = authService.register(request);
            
            // 记录数据迁移结果
            if (response.migration() != null && response.migration().migrated()) {
                log.info("Registration with migration completed: sessions={}", 
                        response.migration().migratedSessionsCount());
            }
            
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
            
        } catch (IllegalArgumentException e) {
            log.warn("Registration failed: {}", e.getMessage());
            throw e;
        }
    }

    /**
     * 用户登录
     * 
     * 如果提供了 guestId，会自动迁移游客的会话数据到该用户下
     * 
     * @param request 登录请求（用户名、密码、可选的 guestId）
     * @return 登录成功响应（包含 Token 和数据迁移信息）
     */
    @PostMapping("/login")
    public ResponseEntity<AuthResponse.Success> login(
            @Valid @RequestBody AuthRequest.LoginRequest request) {
        
        log.info("User login attempt: username={}, hasGuestId={}", 
                request.username(), request.guestId() != null);
        
        try {
            AuthResponse.Success response = authService.login(request);
            
            // 记录数据迁移结果
            if (response.migration() != null && response.migration().migrated()) {
                log.info("Login with migration completed: sessions={}", 
                        response.migration().migratedSessionsCount());
            }
            
            return ResponseEntity.ok(response);
            
        } catch (IllegalArgumentException | IllegalStateException e) {
            log.warn("Login failed: {}", e.getMessage());
            throw e;
        }
    }

    @PostMapping("/password-reset/request")
    public ResponseEntity<PasswordResetResponse> requestPasswordReset(
            @Valid @RequestBody AuthRequest.PasswordResetRequest request) {
        passwordResetService.requestReset(request.email());
        return ResponseEntity.accepted().body(new PasswordResetResponse(
                true,
                "如果该邮箱已注册，重置邮件将在几分钟内送达"
        ));
    }

    @PostMapping("/password-reset/confirm")
    public ResponseEntity<PasswordResetResponse> confirmPasswordReset(
            @Valid @RequestBody AuthRequest.PasswordResetConfirm request) {
        passwordResetService.confirmReset(request.token(), request.newPassword());
        return ResponseEntity.ok(new PasswordResetResponse(true, "密码已重置，请使用新密码登录"));
    }

    /**
     * 刷新 Token
     * 
     * 使用旧 Token 换取新 Token，延长有效期
     * 
     * @param authHeader Authorization 头（Bearer {token}）
     * @return 新的 Token
     */
    @PostMapping("/refresh")
    public ResponseEntity<TokenRefreshResponse> refreshToken(
            @RequestHeader("Authorization") String authHeader) {
        
        String oldToken = jwtUtil.extractTokenFromHeader(authHeader);
        if (oldToken == null) {
            return ResponseEntity.badRequest()
                    .body(new TokenRefreshResponse(null, "Invalid Authorization header"));
        }
        
        try {
            String newToken = authService.refreshToken(oldToken);
            boolean isGuest = jwtUtil.isGuestToken(newToken);
            
            return ResponseEntity.ok(new TokenRefreshResponse(
                    newToken, 
                    isGuest ? "guest" : "user",
                    isGuest ? 604800L : 86400L  // 7天或1天
            ));
            
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new TokenRefreshResponse(null, e.getMessage()));
        }
    }

    /**
     * 用户登出
     * 
     * 客户端应清除本地存储的 Token，服务端可在此进行额外的清理操作
     * 
     * @param authHeader Authorization 头
     * @return 登出成功响应
     */
    @PostMapping("/logout")
    public ResponseEntity<LogoutResponse> logout(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        
        String token = jwtUtil.extractTokenFromHeader(authHeader);
        if (token != null) {
            String subject = jwtUtil.getSubjectFromToken(token);
            log.info("User logout: subject={}", subject);
            
            // 可以在此处进行：
            // 1. 将 Token 加入黑名单（如果使用 Redis 等缓存）
            // 2. 记录登出日志
            // 3. 清理其他相关数据
        }
        
        return ResponseEntity.ok(new LogoutResponse(true, "Logout successful"));
    }

    /**
     * 验证 Token 有效性
     * 
     * 用于前端检查当前 Token 是否仍然有效
     * 
     * @param authHeader Authorization 头
     * @return 验证结果
     */
    @GetMapping("/validate")
    public ResponseEntity<ValidateResponse> validateToken(
            @RequestHeader("Authorization") String authHeader) {
        
        String token = jwtUtil.extractTokenFromHeader(authHeader);
        if (token == null) {
            return ResponseEntity.badRequest()
                    .body(new ValidateResponse(false, "Invalid Authorization header", null, null));
        }
        
        boolean isValid = jwtUtil.validateToken(token);
        if (!isValid) {
            return ResponseEntity.ok(new ValidateResponse(false, "Token expired or invalid", null, null));
        }
        
        String subject = jwtUtil.getSubjectFromToken(token);
        boolean isGuest = jwtUtil.isGuestToken(token);
        String type = jwtUtil.getTokenType(token);
        
        return ResponseEntity.ok(new ValidateResponse(true, "Token valid", type, subject));
    }

    private static String resolveAuthenticatedGuestId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof CustomUserDetails details && details.isGuest()) {
            return details.getUsername();
        }
        return null;
    }

    // ========== 响应记录类 ==========

    public record TokenRefreshResponse(
            String token,
            String tokenType,
            Long expiresIn
    ) {
        public TokenRefreshResponse(String token, String error) {
            this(token, null, null);
        }
    }

    public record LogoutResponse(
            boolean success,
            String message
    ) {}

    public record PasswordResetResponse(boolean success, String message) {}

    public record GuestSnapshotResponse(boolean saved) {}

    public record ValidateResponse(
            boolean valid,
            String message,
            String type,
            String subject
    ) {}
}
