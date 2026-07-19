package com.visionary.repository;

import com.visionary.entity.UserNotification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UserNotificationRepository extends JpaRepository<UserNotification, Long> {

    List<UserNotification> findByUserIdAndIsReadFalseOrderByGmtCreatedDesc(Long userId);

    long countByUserIdAndIsReadFalse(Long userId);
}
