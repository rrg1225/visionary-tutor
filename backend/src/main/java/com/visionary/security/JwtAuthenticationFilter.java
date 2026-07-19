package com.visionary.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * JWT 认证过滤器
 * 
 * 职责：
 * 1. 从请求头提取 JWT Token
 * 2. 验证 Token 有效性（签名、过期时间）
 * 3. 判断用户类型（游客/正式用户）
 * 4. 创建对应的 UserDetails 并设置到 SecurityContext
 * 
 * 渐进式注册（Progressive Profiling）支持：
 * - 游客 Token（subject 以 gst_ 开头）：创建临时的 CustomUserDetails，赋予 ROLE_GUEST
 * - 正式用户 Token（subject 为数字用户ID）：从数据库加载完整用户信息，赋予 ROLE_USER
 * 
 * 权限判断：
 * - ROLE_GUEST 可以访问 /api/learning-sessions/**, /api/ai/** 等基础接口
 * - ROLE_USER 可以访问所有接口，包括 /api/users/**, /api/diagnostic-reports/** 等私密接口
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final CustomUserDetailsService userDetailsService;

    private static final String BEARER_PREFIX = "Bearer ";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        
        String authHeader = request.getHeader("Authorization");
        
        // 1. 检查 Authorization 头
        if (!StringUtils.hasText(authHeader) || !authHeader.startsWith(BEARER_PREFIX)) {
            // 没有 Token，继续过滤链（后续由 Spring Security 的其他配置决定是否放行）
            filterChain.doFilter(request, response);
            return;
        }

        String token = authHeader.substring(BEARER_PREFIX.length());
        
        // 2. 验证 Token
        if (!jwtUtil.validateToken(token)) {
            log.debug("Invalid or expired JWT token");
            filterChain.doFilter(request, response);
            return;
        }

        try {
            // 3. 解析 Token 信息
            String subject = jwtUtil.getSubjectFromToken(token);
            boolean isGuest = jwtUtil.isGuestToken(token);
            
            log.debug("JWT validated: subject={}, isGuest={}", subject, isGuest);

            // 4. 创建或加载 UserDetails
            UserDetails userDetails;
            if (isGuest) {
                // 游客用户：创建临时 UserDetails
                userDetails = userDetailsService.createGuestUserDetails(subject);
                log.debug("Created guest user details: guestId={}", subject);
            } else {
                // 正式用户：从数据库加载用户信息
                try {
                    Long userId = Long.parseLong(subject);
                    userDetails = userDetailsService.loadUserById(userId);
                    log.debug("Loaded user details: userId={}", userId);
                } catch (NumberFormatException e) {
                    log.warn("Invalid user ID format in token: {}", subject);
                    filterChain.doFilter(request, response);
                    return;
                }
            }

            // 5. 创建认证对象并设置到 SecurityContext
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(
                            userDetails,
                            null,  // 凭证（JWT 已经验证过）
                            userDetails.getAuthorities()  // 权限列表（ROLE_GUEST 或 ROLE_USER）
                    );
            
            // 设置请求详情（IP地址、会话ID等）
            authentication.setDetails(
                    new WebAuthenticationDetailsSource().buildDetails(request)
            );

            // 设置到 SecurityContext
            SecurityContextHolder.getContext().setAuthentication(authentication);
            
            log.debug("Authentication set in SecurityContext: user={}, authorities={}", 
                    userDetails.getUsername(), userDetails.getAuthorities());

        } catch (Exception e) {
            log.error("JWT authentication failed", e);
            // 认证失败，清空 SecurityContext
            SecurityContextHolder.clearContext();
        }

        // 继续过滤链
        filterChain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Most auth endpoints are public, but guest quota/context endpoints
        // still need the guest JWT so their controllers can resolve guestId.
        String path = request.getRequestURI();
        boolean guestAuthenticationRequired = path.equals("/api/auth/guest/quota")
                || path.equals("/api/auth/guest/context");
        return (path.startsWith("/api/auth/") && !guestAuthenticationRequired) ||
               path.startsWith("/actuator/health") ||
               path.equals("/error");
    }
}
