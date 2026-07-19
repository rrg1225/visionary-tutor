package com.visionary.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationWebSocketHub {

    private final ObjectMapper objectMapper;
    private final Map<Long, Set<WebSocketSession>> sessionsByUser = new ConcurrentHashMap<>();

    public void register(Long userId, WebSocketSession session) {
        if (userId == null || session == null) {
            return;
        }
        sessionsByUser.computeIfAbsent(userId, ignored -> ConcurrentHashMap.newKeySet()).add(session);
    }

    public void unregister(WebSocketSession session) {
        sessionsByUser.values().forEach(set -> set.remove(session));
        sessionsByUser.entrySet().removeIf(entry -> entry.getValue().isEmpty());
    }

    public void sendToUser(Long userId, Map<String, Object> message) {
        Set<WebSocketSession> sessions = sessionsByUser.get(userId);
        if (sessions == null || sessions.isEmpty()) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(message);
            TextMessage textMessage = new TextMessage(json);
            for (WebSocketSession session : Set.copyOf(sessions)) {
                if (session.isOpen()) {
                    session.sendMessage(textMessage);
                } else {
                    sessions.remove(session);
                }
            }
        } catch (IOException e) {
            log.warn("WebSocket send failed userId={}: {}", userId, e.getMessage());
        }
    }
}
