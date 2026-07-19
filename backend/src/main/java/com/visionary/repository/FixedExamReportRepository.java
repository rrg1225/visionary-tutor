package com.visionary.repository;

import com.visionary.entity.FixedExamReport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.List;

public interface FixedExamReportRepository extends JpaRepository<FixedExamReport, Long> {

    Optional<FixedExamReport> findByAttemptId(Long attemptId);

    List<FixedExamReport> findByUserIdOrderByGmtCreatedDesc(Long userId);
}
