package com.visionary.repository;

import com.visionary.entity.GenerationEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GenerationEventRepository extends JpaRepository<GenerationEvent, Long> {

    List<GenerationEvent> findByTraceIdOrderByOccurredAtAsc(String traceId);

    Optional<GenerationEvent> findFirstByTraceIdOrderByOccurredAtDesc(String traceId);

    boolean existsByTraceId(String traceId);
}
