package com.visionary.repository;

import com.visionary.entity.AgentExecutionLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentExecutionLogRepository extends JpaRepository<AgentExecutionLog, Long> {

    List<AgentExecutionLog> findBySessionIdOrderByGmtCreatedDesc(String sessionId);

    List<AgentExecutionLog> findByStatus(String status);
}
