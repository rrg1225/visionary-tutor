package com.visionary.notification;

import com.visionary.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationWebSocketHandler extends TextWebSocketHandler {

    public static final String ATTR_USER_ID = "userId";

    private final NotificationWebSocketHub webSocketHub;
    private final JwtUtil jwtUtil;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Long userId = (Long) session.getAttributes().get(ATTR_USER_ID);
        if (userId == null) {
            closeQuietly(session, CloseStatus.NOT_ACCEPTABLE);
            return;
        }
        webSocketHub.register(userId, session);
        log.debug("Notification WebSocket connected userId={}", userId);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        if ("ping".equalsIgnoreCase(message.getPayload())) {
            try {
                session.sendMessage(new TextMessage("pong"));
            } catch (Exception ignored) {
                // ignore heartbeat failures
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        webSocketHub.unregister(session);
    }

    private void closeQuietly(WebSocketSession session, CloseStatus status) {
        try {
            session.close(status);
        } catch (Exception ignored) {
            // ignore
        }
    }
}
