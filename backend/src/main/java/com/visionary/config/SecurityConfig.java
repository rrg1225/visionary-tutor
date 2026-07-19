package com.visionary.config;

import com.visionary.security.JwtAuthenticationFilter;
import jakarta.servlet.DispatcherType;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.util.StringUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Spring Security 配置
 * 
 * 渐进式注册（Progressive Profiling）安全策略：
 * 1. 认证接口（/auth/**）完全开放，允许游客创建会话
 * 2. 核心私密接口（/users/**, /diagnostic-reports/**）需要正式用户认证
 * 3. 学习会话接口（/learning-sessions/**）接受游客或正式用户 Token
 * 4. AI 相关接口默认开放，支持游客体验
 * 
 * JWT 鉴权流程：
 * 1. JwtAuthenticationFilter 从请求头提取 Token
 * 2. 验证 Token 有效性（签名、过期时间）
 * 3. 判断用户类型（游客/正式用户）
 * 4. 根据接口权限配置决定是否放行
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final UserDetailsService userDetailsService;
    private final PasswordEncoder passwordEncoder;

    @Value("${CORS_ALLOWED_ORIGINS:https://zhiyexueye.top,http://localhost:5173,http://localhost:3000,http://127.0.0.1:5173}")
    private String corsAllowedOrigins;

    /**
     * 配置安全过滤链
     * 
     * URL 权限规则（按优先级从高到低）：
     * 1. permitAll() - 完全开放（无需任何认证）
     * 2. authenticated() - 需要任意有效 Token（游客或正式用户）
     * 3. hasRole() / hasAuthority() - 需要特定角色
     * 4. anyRequest().denyAll() - 默认拒绝（未匹配到的请求）
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // 禁用 CSRF（使用 JWT 的无状态认证，CSRF 不适用）
            .csrf(csrf -> csrf.disable())
            
            // 配置 CORS（跨域资源共享）
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            
            // 无状态会话管理（JWT 不需要服务端 Session）
            .sessionManagement(session -> 
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            
            // URL 权限配置
            .authorizeHttpRequests(auth -> auth
                .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                // OPTIONS 预检请求放行（CORS 必需）
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                
                // ===== 完全开放的接口（permitAll） =====
                // 认证相关
                .requestMatchers("/api/auth/**").permitAll()
                // 健康检查
                .requestMatchers("/actuator/health", "/actuator/info", "/api/health", "/api/meta/build").permitAll()
                // Read-only RAG diagnostic is used by local evaluation scripts.
                .requestMatchers(HttpMethod.GET, "/api/admin/knowledge/rag-diagnostic").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/demo/seed").permitAll()
                // 错误处理
                .requestMatchers("/error").permitAll()
                
                // ===== 需要正式用户认证的接口（hasRole USER） =====
                // 用户资料管理（涉及个人信息修改）
                .requestMatchers("/api/users/me").hasRole("USER")
                .requestMatchers("/api/users/**").hasRole("ADMIN")
                .requestMatchers("/api/feedback/**").hasRole("USER")
                .requestMatchers("/api/questions/**").hasRole("USER")
                .requestMatchers("/api/learning-state-reports/**").hasRole("USER")
                .requestMatchers("/api/ops/**").hasRole("ADMIN")
                // 诊断报告（涉及学习数据分析）
                .requestMatchers("/api/diagnostic-reports/**").hasRole("USER")
                // 评估上传结果查看（深度功能）
                .requestMatchers("/api/assessments/*/report").hasRole("USER")
                
                // ===== 接受游客或正式用户的接口（authenticated） =====
                // 学习会话（核心功能，游客也可使用）
                .requestMatchers("/api/learning-sessions/**").authenticated()
                // AI 对话（游客体验功能）
                .requestMatchers("/api/ai/**").authenticated()
                // SSE 流式对话 / RAG 生成
                .requestMatchers("/api/stream/**").authenticated()
                // 学习画像抽取
                .requestMatchers("/api/profile/**").authenticated()
                // 记忆管理与学习路径进度
                .requestMatchers("/api/memory/**").authenticated()
                .requestMatchers("/api/privacy/**").authenticated()
                .requestMatchers("/api/learning-path/**").authenticated()
                // 个性化资源生成与媒体轮询
                .requestMatchers("/api/resources/**").authenticated()
                .requestMatchers("/api/agent/**").authenticated()
                .requestMatchers("/api/admin/rag-eval/**").authenticated()
                // 治理追踪（返修审计）
                .requestMatchers("/api/v1/resources/**").authenticated()
                // Learning OS 进度流 / 学习者状态
                .requestMatchers("/api/learning-os/**").authenticated()
                .requestMatchers("/api/learner/**").authenticated()
                // 辅导多模态生成
                .requestMatchers("/api/tutoring/**").authenticated()
                // 共享教材库与主动推荐
                .requestMatchers("/api/library/**").authenticated()
                .requestMatchers("/api/knowledge-content/**").authenticated()
                .requestMatchers("/api/fixed-exams/**").hasRole("USER")
                // 云端 TTS 与通知
                .requestMatchers("/api/tts/**").authenticated()
                .requestMatchers("/api/sandbox/**").authenticated()
                .requestMatchers("/api/notifications/**").authenticated()
                // WebSocket 握手（JWT 在 HandshakeInterceptor 校验）
                .requestMatchers("/ws/**").permitAll()
                // 评估提交（上传后处理需要认证；Controller 使用 /api/assessment 单数路径）
                .requestMatchers("/api/assessment/**").authenticated()
                .requestMatchers("/api/assessments/**").authenticated()
                
                // 其他所有请求默认拒绝（安全最佳实践）
                .anyRequest().denyAll()
            )
            // 未认证和无权限必须区分为 401/403；前端仅在 401 时刷新 Token。
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((request, response, exception) ->
                    response.sendError(jakarta.servlet.http.HttpServletResponse.SC_UNAUTHORIZED))
                .accessDeniedHandler((request, response, exception) ->
                    response.sendError(jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN))
            )
            
            // 添加 JWT 认证过滤器
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * 配置 CORS
     * 
     * 允许前端开发服务器跨域访问
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        
        // 生产域名和本地开发地址均通过白名单管理，可由环境变量覆盖。
        List<String> allowedOrigins = Arrays.stream(corsAllowedOrigins.split(","))
            .map(String::trim)
            .filter(StringUtils::hasText)
            .toList();
        if (allowedOrigins.isEmpty()) {
            throw new IllegalStateException("CORS_ALLOWED_ORIGINS must contain at least one origin");
        }
        configuration.setAllowedOrigins(allowedOrigins);
        
        // 允许的 HTTP 方法
        configuration.setAllowedMethods(Arrays.asList(
            "GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"
        ));
        
        // 允许的请求头
        configuration.setAllowedHeaders(Arrays.asList(
            "Authorization",
            "Content-Type",
            "X-Requested-With",
            "Accept",
            "Origin",
            "X-Guest-Id"  // 自定义头：用于传递游客ID
        ));
        
        // 暴露的响应头
        configuration.setExposedHeaders(List.of(
            "Authorization",
            "X-Auth-Token",
            "X-Pptx-Export-Mode"
        ));
        
        // 允许携带凭证（cookies）
        configuration.setAllowCredentials(true);
        
        // 预检请求缓存时间（秒）
        configuration.setMaxAge(3600L);
        
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        
        return source;
    }

    /**
     * 配置认证管理器
     * 
     * 用于 Spring Security 的 DaoAuthenticationProvider
     */
    @Bean
    public AuthenticationManager authenticationManager() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }
}
