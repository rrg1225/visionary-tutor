package com.visionary.repository;

import com.visionary.entity.QuestionAttempt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface QuestionAttemptRepository extends JpaRepository<QuestionAttempt, Long> {
    List<QuestionAttempt> findByUserIdAndCorrectFalseOrderByGmtCreatedDesc(Long userId);

    List<QuestionAttempt> findByUserIdAndCorrectFalseAndNextReviewAtLessThanEqualOrderByNextReviewAtAsc(
            Long userId,
            LocalDateTime now
    );
}
