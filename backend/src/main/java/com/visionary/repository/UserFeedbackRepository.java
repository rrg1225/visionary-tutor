package com.visionary.repository;

import com.visionary.entity.UserFeedback;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UserFeedbackRepository extends JpaRepository<UserFeedback, Long> {
    List<UserFeedback> findByUserIdOrderByGmtCreatedDesc(Long userId);
}
