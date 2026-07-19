package com.visionary.repository;

import com.visionary.entity.LearningStateReport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LearningStateReportRepository extends JpaRepository<LearningStateReport, Long> {

    List<LearningStateReport> findTop100ByUserIdOrderByGmtCreatedDesc(Long userId);

    List<LearningStateReport> findByUserIdAndContextTypeAndContextKeyOrderByGmtCreatedDesc(
            Long userId, String contextType, String contextKey);
}
