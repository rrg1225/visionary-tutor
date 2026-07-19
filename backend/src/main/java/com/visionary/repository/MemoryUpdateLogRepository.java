package com.visionary.repository;

import com.visionary.entity.MemoryUpdateLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MemoryUpdateLogRepository extends JpaRepository<MemoryUpdateLog, Long> {

    List<MemoryUpdateLog> findByUserIdOrderByGmtCreatedDesc(Long userId);
}
