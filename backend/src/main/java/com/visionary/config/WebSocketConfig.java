package com.visionary.config;

import com.visionary.notification.NotificationWebSocketHandler;
import com.visionary.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.net.URI;
import java.util.Map;

@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final NotificationWebSocketHandler notificationWebSocketHandler;
    private final JwtUtil jwtUtil;

    @Value("${CORS_ALLOWED_ORIGINS:https://zhiyexueye.top,http://localhost:5173,http://localhost:3000,http://127.0.0.1:5173}")
    private String allowedOrigins;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(notificationWebSocketHandler, "/ws/notifications")
                .addInterceptors(new JwtHandshakeInterceptor())
                .setAllowedOrigins(parseAllowedOrigins());
    }

    private String[] parseAllowedOrigins() {
        return java.util.Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isBlank())
                .distinct()
                .toArray(String[]::new);
    }

    private class JwtHandshakeInterceptor implements HandshakeInterceptor {

        @Override
        public boolean beforeHandshake(
                ServerHttpRequest request,
                ServerHttpResponse response,
                WebSocketHandler wsHandler,
                Map<String, Object> attributes
        ) {
            String token = extractToken(request.getURI());
            if (token == null || !jwtUtil.validateToken(token) || jwtUtil.isGuestToken(token)) {
                return false;
            }
            try {
                Long userId = Long.parseLong(jwtUtil.getSubjectFromToken(token));
                attributes.put(NotificationWebSocketHandler.ATTR_USER_ID, userId);
                return true;
            } catch (NumberFormatException e) {
                return false;
            }
        }

        @Override
        public void afterHandshake(
                ServerHttpRequest request,
                ServerHttpResponse response,
                WebSocketHandler wsHandler,
                Exception exception
        ) {
            // no-op
        }

        private String extractToken(URI uri) {
            if (uri == null || uri.getQuery() == null) {
                return null;
            }
            for (String part : uri.getQuery().split("&")) {
                String[] kv = part.split("=", 2);
                if (kv.length == 2 && "token".equals(kv[0])) {
                    return java.net.URLDecoder.decode(kv[1], java.nio.charset.StandardCharsets.UTF_8);
                }
            }
            return null;
        }
    }
}
