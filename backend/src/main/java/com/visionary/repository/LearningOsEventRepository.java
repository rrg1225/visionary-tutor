package com.visionary.repository;

import com.visionary.entity.LearningOsEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LearningOsEventRepository extends JpaRepository<LearningOsEvent, Long> {

    List<LearningOsEvent> findTop20ByUserIdOrderByGmtCreatedDesc(Long userId);
}
