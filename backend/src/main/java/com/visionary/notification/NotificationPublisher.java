package com.visionary.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.visionary.entity.UserNotification;
import com.visionary.repository.UserNotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationPublisher {

    private final UserNotificationRepository notificationRepository;
    private final NotificationWebSocketHub webSocketHub;
    private final ObjectMapper objectMapper;

    @Transactional
    public void publish(Long userId, NotificationType type, Map<String, Object> payload) {
        if (userId == null || type == null) {
            return;
        }
        String payloadJson = toJson(payload);
        UserNotification saved = new UserNotification();
        saved.setUserId(userId);
        saved.setNotificationType(type.name());
        saved.setPayloadJson(payloadJson);
        saved.setIsRead(false);
        saved = notificationRepository.save(saved);

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("id", saved.getId());
        message.put("type", type.name());
        message.put("payload", payload);
        message.put("read", false);
        message.put("createdAt", saved.getGmtCreated());
        webSocketHub.sendToUser(userId, message);
        log.debug("Notification published userId={} type={}", userId, type);
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload == null ? Map.of() : payload);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
