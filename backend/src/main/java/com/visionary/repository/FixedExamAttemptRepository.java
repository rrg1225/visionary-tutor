package com.visionary.repository;

import com.visionary.entity.FixedExamAttempt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface FixedExamAttemptRepository extends JpaRepository<FixedExamAttempt, Long> {

    Optional<FixedExamAttempt> findTopByUserIdAndPaperCodeAndStatusOrderByGmtModifiedDesc(
            Long userId,
            String paperCode,
            String status
    );
}
