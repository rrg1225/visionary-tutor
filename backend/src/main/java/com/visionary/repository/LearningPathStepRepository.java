package com.visionary.repository;

import com.visionary.entity.LearningPathStep;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface LearningPathStepRepository extends JpaRepository<LearningPathStep, Long> {

    List<LearningPathStep> findByUserIdAndLearningSessionIdOrderByStepOrderAsc(Long userId, Long learningSessionId);

    Optional<LearningPathStep> findByUserIdAndLearningSessionIdAndStepOrder(Long userId, Long learningSessionId, Integer stepOrder);

    @Modifying(flushAutomatically = true)
    @Query("delete from LearningPathStep step "
            + "where step.userId = :userId and step.learningSessionId = :learningSessionId")
    int deleteByUserIdAndLearningSessionId(
            @Param("userId") Long userId,
            @Param("learningSessionId") Long learningSessionId
    );
}
