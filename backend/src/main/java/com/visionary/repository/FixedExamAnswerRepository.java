package com.visionary.repository;

import com.visionary.entity.FixedExamAnswer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FixedExamAnswerRepository extends JpaRepository<FixedExamAnswer, Long> {

    List<FixedExamAnswer> findByAttemptIdOrderByIdAsc(Long attemptId);

    Optional<FixedExamAnswer> findByAttemptIdAndQuestionId(Long attemptId, String questionId);
}
