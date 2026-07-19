package com.visionary.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * JWT 工具类 - 支持游客和正式用户双模式
 * 
 * 设计原则：
 * 1. 游客 Token 使用前缀 "gst_" 的用户ID，配合 claim "isGuest": true
 * 2. 正式用户 Token 使用数字用户ID，claim "isGuest": false
 * 3. 游客 Token 有效期较短（默认 7 天），正式用户 Token 较长（默认 30 天）
 * 4. Token 中包含用户类型标识，便于过滤器快速判断
 */
@Component
public class JwtUtil {

    @Value("${jwt.secret:visionary-tutor-jwt-secret-key-must-be-32-bytes-min-for-hs256-1234567890}")
    private String secret;

    @Value("${jwt.expiration-ms:86400000}")
    private long expirationMs;  // 默认 1 天

    @Value("${jwt.guest-expiration-ms:604800000}")
    private long guestExpirationMs;  // 游客默认 7 天

    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 生成正式用户的 JWT Token
     * 
     * @param userId 正式用户ID（数字）
     * @return JWT Token 字符串
     */
    public String generateToken(Long userId) {
        return generateToken(userId.toString(), false);
    }

    /**
     * 生成游客的 JWT Token
     * 
     * @param guestId 游客ID（格式：gst_{uuid}）
     * @return JWT Token 字符串
     */
    public String generateGuestToken(String guestId) {
        return generateToken(guestId, true);
    }

    /**
     * 生成 JWT Token（内部方法）
     * 
     * @param subject 用户标识（userId 或 guestId）
     * @param isGuest 是否为游客
     * @return JWT Token 字符串
     */
    public String generateToken(String subject, boolean isGuest) {
        Date now = new Date();
        long expiration = isGuest ? guestExpirationMs : expirationMs;
        Date expiryDate = new Date(now.getTime() + expiration);

        Map<String, Object> claims = new HashMap<>();
        claims.put("isGuest", isGuest);
        claims.put("type", isGuest ? "GUEST" : "USER");
        claims.put("iat", now.getTime() / 1000);

        return Jwts.builder()
                .subject(subject)
                .claims(claims)
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(getSigningKey())
                .compact();
    }

    /**
     * 从 Token 中提取用户标识（userId 或 guestId）
     */
    public String getSubjectFromToken(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    /**
     * 判断 Token 是否属于游客
     */
    public boolean isGuestToken(String token) {
        try {
            Claims claims = extractAllClaims(token);
            Boolean isGuest = claims.get("isGuest", Boolean.class);
            return isGuest != null && isGuest;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * 提取 Token 中的用户类型
     * @return "GUEST" 或 "USER"
     */
    public String getTokenType(String token) {
        try {
            Claims claims = extractAllClaims(token);
            String type = claims.get("type", String.class);
            return type != null ? type : (isGuestToken(token) ? "GUEST" : "USER");
        } catch (JwtException | IllegalArgumentException e) {
            return "UNKNOWN";
        }
    }

    /**
     * 验证 Token 是否有效
     * 
     * @param token JWT Token
     * @return 是否有效
     */
    public boolean validateToken(String token) {
        try {
            extractAllClaims(token);
            return !isTokenExpired(token);
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * 验证 Token 是否有效，并匹配指定的用户标识
     * 
     * @param token JWT Token
     * @param userId 期望的用户标识
     * @return 是否匹配
     */
    public boolean validateToken(String token, String userId) {
        try {
            final String subject = getSubjectFromToken(token);
            return (subject.equals(userId) || subject.equals(userId.toString())) && !isTokenExpired(token);
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * 判断 Token 是否即将过期（默认 5 分钟内）
     * 
     * @param token JWT Token
     * @return 是否即将过期
     */
    public boolean isTokenAboutToExpire(String token) {
        return isTokenAboutToExpire(token, 5 * 60 * 1000); // 5 分钟
    }

    /**
     * 判断 Token 是否在指定时间内过期
     * 
     * @param token JWT Token
     * @param thresholdMs 时间阈值（毫秒）
     * @return 是否即将过期
     */
    public boolean isTokenAboutToExpire(String token, long thresholdMs) {
        try {
            Date expiration = extractClaim(token, Claims::getExpiration);
            return expiration.getTime() - System.currentTimeMillis() < thresholdMs;
        } catch (JwtException | IllegalArgumentException e) {
            return true;
        }
    }

    /**
     * 刷新 Token（延长有效期）
     * 
     * @param token 原 Token
     * @return 新的 Token
     */
    public String refreshToken(String token) {
        String subject = getSubjectFromToken(token);
        boolean isGuest = isGuestToken(token);
        return generateToken(subject, isGuest);
    }

    /**
     * 提取指定 claim
     */
    public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = extractAllClaims(token);
        return claimsResolver.apply(claims);
    }

    /**
     * 提取所有 claims
     */
    private Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * 检查 Token 是否已过期
     */
    private boolean isTokenExpired(String token) {
        return extractClaim(token, Claims::getExpiration).before(new Date());
    }

    /**
     * 从请求头中提取 Token
     * 
     * @param authHeader Authorization 头值（格式：Bearer {token}）
     * @return Token 字符串，如果不存在或格式错误返回 null
     */
    public String extractTokenFromHeader(String authHeader) {
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        return null;
    }
}
