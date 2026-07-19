package com.visionary.service;

import com.visionary.dto.NotificationDto;
import com.visionary.entity.UserNotification;
import com.visionary.repository.UserNotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class NotificationService {

    private final UserNotificationRepository notificationRepository;

    @Transactional(readOnly = true)
    public List<NotificationDto> listUnread(Long userId) {
        return notificationRepository.findByUserIdAndIsReadFalseOrderByGmtCreatedDesc(userId)
                .stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional
    public void markRead(Long userId, Long notificationId) {
        if (userId == null || notificationId == null) {
            return;
        }
        notificationRepository.findById(notificationId).ifPresent(notification -> {
            if (userId.equals(notification.getUserId())) {
                notification.setIsRead(true);
                notificationRepository.save(notification);
            }
        });
    }

    private NotificationDto toDto(UserNotification notification) {
        return new NotificationDto(
                notification.getId(),
                notification.getNotificationType(),
                notification.getPayloadJson(),
                Boolean.TRUE.equals(notification.getIsRead()),
                notification.getGmtCreated()
        );
    }
}
