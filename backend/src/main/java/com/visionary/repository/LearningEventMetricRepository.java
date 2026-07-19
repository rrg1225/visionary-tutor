package com.visionary.repository;

import com.visionary.entity.LearningEventMetric;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface LearningEventMetricRepository extends JpaRepository<LearningEventMetric, Long> {

    List<LearningEventMetric> findByUserIdOrderByEventTimeDesc(Long userId);

    List<LearningEventMetric> findByLearningSessionIdOrderByEventTimeDesc(Long sessionId);

    void deleteByLearningSessionId(Long learningSessionId);

    java.util.Optional<LearningEventMetric> findFirstByUserIdAndLearningSessionIdAndMetricTypeAndConceptOrderByEventTimeDesc(
            Long userId,
            Long learningSessionId,
            String metricType,
            String concept
    );

    @Query("SELECT m FROM LearningEventMetric m WHERE m.userId = :userId AND m.metricType = :type ORDER BY m.eventTime DESC")
    List<LearningEventMetric> findRecentByType(@Param("userId") Long userId, @Param("type") String type);

    @Query(value = """
        SELECT concept, AVG(value_numeric) as avg_accuracy
        FROM learning_metrics
        WHERE user_id = :userId AND metric_type = 'QUIZ_ACCURACY' AND concept IS NOT NULL
        GROUP BY concept
        """, nativeQuery = true)
    List<Object[]> getAverageAccuracyPerConcept(@Param("userId") Long userId);
}
