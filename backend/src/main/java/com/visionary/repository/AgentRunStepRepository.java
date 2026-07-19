package com.visionary.repository;

import com.visionary.entity.AgentRunStep;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentRunStepRepository extends JpaRepository<AgentRunStep, Long> {

    List<AgentRunStep> findByRunIdOrderByStepOrderAsc(String runId);

    List<AgentRunStep> findByLearningSessionIdOrderByGmtCreatedDesc(Long learningSessionId);

    void deleteByLearningSessionId(Long learningSessionId);
}
