package com.visionary.service;

import com.visionary.entity.LearningSession;
import com.visionary.entity.QuestionAttempt;
import com.visionary.repository.LearningSessionRepository;
import com.visionary.repository.QuestionAttemptRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;

@Service
@RequiredArgsConstructor
public class QuestionBankService {

    private static final int MAX_WRONG_BOOK_ITEMS = 200;
    private final QuestionAttemptRepository attemptRepository;
    private final LearningSessionRepository learningSessionRepository;
    private final LearningEvidenceService learningEvidenceService;

    @Transactional
    public List<QuestionAttempt> recordAttempts(Long userId, List<AttemptInput> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            return List.of();
        }
        if (inputs.size() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "一次最多记录 100 道题");
        }
        LocalDateTime now = LocalDateTime.now();
        List<QuestionAttempt> attempts = inputs.stream()
                .map(input -> toEntity(userId, input, now))
                .toList();
        List<QuestionAttempt> saved = attemptRepository.saveAll(attempts);
        saved.forEach(attempt -> learningEvidenceService.record(new LearningEvidenceService.Evidence(
                userId, attempt.getLearningSessionId(), "QUESTION_ATTEMPT",
                attempt.getSourceType(), null, null, attempt.getSourceQuestionId(),
                String.valueOf(attempt.getId()), null, null,
                attempt.getFixedExamAttemptId() == null ? null : String.valueOf(attempt.getFixedExamAttemptId()),
                null, java.util.Map.<String, Object>of("correct", attempt.isCorrect(), "skipped", attempt.isSkipped())
        )));
        return saved;
    }

    @Transactional(readOnly = true)
    public List<QuestionAttempt> wrongBook(Long userId) {
        return attemptRepository.findByUserIdAndCorrectFalseOrderByGmtCreatedDesc(userId)
                .stream()
                .limit(MAX_WRONG_BOOK_ITEMS)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<QuestionAttempt> dueReviews(Long userId) {
        return attemptRepository
                .findByUserIdAndCorrectFalseAndNextReviewAtLessThanEqualOrderByNextReviewAtAsc(userId, LocalDateTime.now())
                .stream()
                .limit(50)
                .toList();
    }

    @Transactional
    public QuestionAttempt review(Long userId, Long attemptId, boolean correct) {
        QuestionAttempt attempt = attemptRepository.findById(attemptId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "错题记录不存在"));
        if (!attempt.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "不能修改其他用户的错题记录");
        }
        int count = attempt.getReviewCount() + 1;
        attempt.setReviewCount(count);
        attempt.setLastReviewedAt(LocalDateTime.now());
        attempt.setCorrect(correct);
        if (correct) {
            attempt.setReviewStatus("MASTERED");
            attempt.setNextReviewAt(null);
        } else {
            int[] intervals = {1, 3, 7, 14};
            int days = intervals[Math.min(count - 1, intervals.length - 1)];
            attempt.setReviewStatus("DUE");
            attempt.setNextReviewAt(LocalDateTime.now().plusDays(days));
        }
        return attemptRepository.save(attempt);
    }

    private QuestionAttempt toEntity(Long userId, AttemptInput input, LocalDateTime now) {
        String prompt = trim(input.prompt(), 5000);
        if (prompt.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "题目内容不能为空");
        }
        validateOwnedSession(userId, input.learningSessionId());
        boolean wrong = !input.correct() || input.skipped();
        return QuestionAttempt.builder()
                .userId(userId)
                .learningSessionId(input.learningSessionId())
                .sourceType(trim(input.sourceType(), 32))
                .sourceQuestionId(trim(input.sourceQuestionId(), 96))
                .fixedExamAttemptId(input.fixedExamAttemptId())
                .questionKey(nonBlank(input.questionKey(), hash(prompt)))
                .prompt(prompt)
                .userAnswer(trim(input.userAnswer(), 5000))
                .correctAnswer(trim(input.correctAnswer(), 5000))
                .explanation(trim(input.explanation(), 12000))
                .concept(trim(input.concept(), 128))
                .correct(!wrong)
                .skipped(input.skipped())
                .viewedAnswerBeforeSubmit(input.viewedAnswerBeforeSubmit())
                .durationSeconds(Math.max(0, input.durationSeconds()))
                .reviewStatus(wrong ? "DUE" : "MASTERED")
                .reviewCount(0)
                .nextReviewAt(wrong ? now.plusDays(1) : null)
                .build();
    }

    private void validateOwnedSession(Long userId, Long sessionId) {
        if (sessionId == null) {
            return;
        }
        LearningSession session = learningSessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "学习会话不存在"));
        if (!userId.equals(session.getUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "学习会话不属于当前用户");
        }
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 32);
        } catch (Exception ignored) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : trim(value, 96);
    }

    private static String trim(String value, int max) {
        String text = value == null ? "" : value.trim();
        return text.length() <= max ? text : text.substring(0, max);
    }

    public record AttemptInput(
            Long learningSessionId,
            String questionKey,
            String prompt,
            String userAnswer,
            String correctAnswer,
            String explanation,
            String concept,
            boolean correct,
            boolean skipped,
            String sourceType,
            String sourceQuestionId,
            Long fixedExamAttemptId,
            boolean viewedAnswerBeforeSubmit,
            int durationSeconds
    ) {
        public AttemptInput(
                Long learningSessionId,
                String questionKey,
                String prompt,
                String userAnswer,
                String correctAnswer,
                String explanation,
                String concept,
                boolean correct,
                boolean skipped
        ) {
            this(
                    learningSessionId,
                    questionKey,
                    prompt,
                    userAnswer,
                    correctAnswer,
                    explanation,
                    concept,
                    correct,
                    skipped,
                    null,
                    null,
                    null,
                    false,
                    0
            );
        }
    }
}
