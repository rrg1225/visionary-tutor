package com.visionary.repository;

import com.visionary.entity.ResourceRecommendationLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ResourceRecommendationLogRepository extends JpaRepository<ResourceRecommendationLog, Long> {

    Optional<ResourceRecommendationLog> findFirstByUserIdAndConsumedFalseOrderByGmtCreatedDesc(Long userId);

    List<ResourceRecommendationLog> findTop5ByUserIdOrderByGmtCreatedDesc(Long userId);
}
