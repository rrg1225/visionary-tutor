package com.visionary.repository;

import com.visionary.entity.DiagnosticReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DiagnosticReportRepository extends JpaRepository<DiagnosticReport, Long> {

    @Query("SELECT dr FROM DiagnosticReport dr WHERE dr.learningSessionId = :sessionId ORDER BY dr.gmtCreated DESC")
    List<DiagnosticReport> findByLearningSessionIdOrderByCreatedDesc(@Param("sessionId") Long learningSessionId);

    @Query("SELECT dr FROM DiagnosticReport dr LEFT JOIN FETCH dr.weakNodes WHERE dr.id = :id")
    Optional<DiagnosticReport> findByIdWithWeakNodes(@Param("id") Long id);

    @Query("""
            SELECT dr FROM DiagnosticReport dr
            WHERE dr.learningSessionId IN (
                SELECT ls.id FROM LearningSession ls WHERE ls.userId = :userId
            )
            ORDER BY dr.gmtCreated DESC
            """)
    List<DiagnosticReport> findRecentByUserId(@Param("userId") Long userId);
}
