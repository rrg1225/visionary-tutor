package com.visionary.repository;

import com.visionary.entity.ResourceUsageRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ResourceUsageRecordRepository extends JpaRepository<ResourceUsageRecord, Long> {

    List<ResourceUsageRecord> findByUserIdOrderByGmtCreatedDesc(Long userId);

    List<ResourceUsageRecord> findByUserIdAndLearningSessionIdOrderByGmtCreatedDesc(Long userId, Long learningSessionId);

    void deleteByLearningSessionId(Long learningSessionId);
}
