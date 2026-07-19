package com.visionary.exam;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.visionary.entity.FixedExamAnswer;
import com.visionary.entity.FixedExamAttempt;
import com.visionary.entity.FixedExamReport;
import com.visionary.entity.LearningSession;
import com.visionary.repository.FixedExamAnswerRepository;
import com.visionary.repository.FixedExamAttemptRepository;
import com.visionary.repository.FixedExamReportRepository;
import com.visionary.repository.LearningSessionRepository;
import com.visionary.service.QuestionBankService;
import com.visionary.service.LearningEvidenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.visionary.exam.FixedExamDtos.AnswerDraft;
import static com.visionary.exam.FixedExamDtos.AnswerReview;
import static com.visionary.exam.FixedExamDtos.AttemptView;
import static com.visionary.exam.FixedExamDtos.ExamReportView;
import static com.visionary.exam.FixedExamDtos.MasteryResult;
import static com.visionary.exam.FixedExamDtos.QuestionResult;
import static com.visionary.exam.FixedExamDtos.SaveAnswerRequest;
import static com.visionary.exam.FixedExamDtos.ScoringPointResult;

@Service
@RequiredArgsConstructor
public class FixedExamAttemptService {

    private static final String IN_PROGRESS = "IN_PROGRESS";
    private static final String SUBMITTED = "SUBMITTED";
    private static final int MAX_ANSWER_CHARS = 20_000;

    private final FixedExamCatalogService catalogService;
    private final FixedExamAttemptRepository attemptRepository;
    private final FixedExamAnswerRepository answerRepository;
    private final FixedExamReportRepository reportRepository;
    private final LearningSessionRepository learningSessionRepository;
    private final QuestionBankService questionBankService;
    private final ObjectMapper objectMapper;
    private final LearningEvidenceService learningEvidenceService;

    @Transactional
    public AttemptView startAttempt(Long userId, String paperCode, Long learningSessionId) {
        FixedExamCatalog.Paper paper = catalogService.requirePaper(paperCode);
        validateOwnedSession(userId, learningSessionId);
        FixedExamAttempt attempt = attemptRepository
                .findTopByUserIdAndPaperCodeAndStatusOrderByGmtModifiedDesc(userId, paper.code(), IN_PROGRESS)
                .orElseGet(() -> createAttempt(userId, paper, learningSessionId));
        if (attempt.getLearningSessionId() == null && learningSessionId != null) {
            attempt.setLearningSessionId(learningSessionId);
            attemptRepository.save(attempt);
        }
        ensureAnswerRows(attempt, paper);
        return toAttemptView(attempt);
    }

    @Transactional(readOnly = true)
    public AttemptView getAttempt(Long userId, Long attemptId) {
        return toAttemptView(requireOwnedAttempt(userId, attemptId));
    }

    @Transactional
    public AnswerDraft saveAnswer(Long userId, Long attemptId, String questionId, SaveAnswerRequest request) {
        FixedExamAttempt attempt = requireOwnedAttempt(userId, attemptId);
        requireInProgress(attempt);
        FixedExamCatalog.Question question = catalogService.requireQuestion(attempt.getPaperCode(), questionId);
        FixedExamAnswer answer = answerRepository.findByAttemptIdAndQuestionId(attemptId, question.id())
                .orElseGet(() -> newAnswer(attemptId, question));
        answer.setUserAnswer(trim(request == null ? null : request.userAnswer(), MAX_ANSWER_CHARS));
        answer.setDurationSeconds(clampDuration(request == null ? null : request.durationSeconds()));
        answer.setRevisionCount(answer.getRevisionCount() + 1);
        answer.setDraft(true);
        answerRepository.save(answer);
        updateAttemptDuration(attempt);
        return toAnswerDraft(answer);
    }

    @Transactional
    public AnswerReview revealAnswer(Long userId, Long attemptId, String questionId) {
        FixedExamAttempt attempt = requireOwnedAttempt(userId, attemptId);
        FixedExamCatalog.Question question = catalogService.requireQuestion(attempt.getPaperCode(), questionId);
        if (IN_PROGRESS.equals(attempt.getStatus())) {
            FixedExamAnswer answer = answerRepository.findByAttemptIdAndQuestionId(attemptId, question.id())
                    .orElseGet(() -> newAnswer(attemptId, question));
            answer.setViewedAnswerBeforeSubmit(true);
            answerRepository.save(answer);
        }
        return toAnswerReview(question);
    }

    @Transactional
    public ExamReportView submit(Long userId, Long attemptId) {
        FixedExamAttempt attempt = requireOwnedAttempt(userId, attemptId);
        if (SUBMITTED.equals(attempt.getStatus())) {
            return getReport(userId, attemptId);
        }
        requireInProgress(attempt);
        FixedExamCatalog.Paper paper = catalogService.requirePaper(attempt.getPaperCode());
        ensureAnswerRows(attempt, paper);
        Map<String, FixedExamAnswer> answers = new LinkedHashMap<>();
        for (FixedExamAnswer answer : answerRepository.findByAttemptIdOrderByIdAsc(attemptId)) {
            answers.put(answer.getQuestionId(), answer);
        }

        List<QuestionResult> results = new ArrayList<>();
        List<QuestionBankService.AttemptInput> wrongBookInputs = new ArrayList<>();
        BigDecimal totalScore = BigDecimal.ZERO;
        int totalDuration = 0;
        int answeredCount = 0;
        int correctCount = 0;
        for (FixedExamCatalog.Question question : paper.questions()) {
            FixedExamAnswer answer = answers.get(question.id());
            QuestionResult result = grade(question, answer);
            results.add(result);
            totalScore = totalScore.add(result.score());
            totalDuration += result.durationSeconds();
            if (!result.unanswered()) {
                answeredCount++;
            }
            if (result.correct()) {
                correctCount++;
            }

            answer.setScore(result.score());
            answer.setCorrect(result.correct());
            answer.setDraft(false);
            answer.setGradingJson(writeJson(result.scoringPoints()));
            answerRepository.save(answer);

            if (!result.correct()) {
                wrongBookInputs.add(toWrongBookInput(attempt, question, result));
            }
        }

        BigDecimal maxScore = paper.questions().stream()
                .map(FixedExamCatalog.Question::maxScore)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        attempt.setStatus(SUBMITTED);
        attempt.setSubmittedAt(LocalDateTime.now());
        attempt.setTotalScore(totalScore);
        attempt.setMaxScore(maxScore);
        attempt.setTotalDurationSeconds(totalDuration);
        attemptRepository.save(attempt);

        ExamReportView draftReport = buildReport(
                null,
                attempt,
                paper,
                results,
                totalScore,
                maxScore,
                answeredCount,
                correctCount
        );
        FixedExamReport reportEntity = new FixedExamReport();
        reportEntity.setAttemptId(attemptId);
        reportEntity.setUserId(userId);
        reportEntity.setLearningSessionId(attempt.getLearningSessionId());
        reportEntity.setPaperCode(paper.code());
        reportEntity.setReportJson(writeJson(draftReport));
        FixedExamReport savedReport = reportRepository.save(reportEntity);
        ExamReportView finalReport = withReportId(draftReport, savedReport.getId());
        savedReport.setReportJson(writeJson(finalReport));
        reportRepository.save(savedReport);
        learningEvidenceService.record(new LearningEvidenceService.Evidence(
                userId, attempt.getLearningSessionId(), "FIXED_EXAM_REPORT", null, null,
                paper.code(), null, String.valueOf(attemptId), null, null,
                String.valueOf(savedReport.getId()), null,
                Map.<String, Object>of("score", totalScore, "maxScore", maxScore, "answeredCount", answeredCount)
        ));

        if (!wrongBookInputs.isEmpty()) {
            questionBankService.recordAttempts(userId, wrongBookInputs);
        }
        return finalReport;
    }

    @Transactional(readOnly = true)
    public ExamReportView getReport(Long userId, Long attemptId) {
        requireOwnedAttempt(userId, attemptId);
        FixedExamReport report = reportRepository.findByAttemptId(attemptId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "题卷报告尚未生成"));
        try {
            return objectMapper.readValue(report.getReportJson(), ExamReportView.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored fixed exam report is invalid", exception);
        }
    }

    @Transactional(readOnly = true)
    public List<ExamReportView> listReports(Long userId) {
        return reportRepository.findByUserIdOrderByGmtCreatedDesc(userId).stream()
                .map(report -> {
                    try {
                        return objectMapper.readValue(report.getReportJson(), ExamReportView.class);
                    } catch (JsonProcessingException exception) {
                        throw new IllegalStateException("Stored fixed exam report is invalid: " + report.getId(), exception);
                    }
                })
                .toList();
    }

    private FixedExamAttempt createAttempt(Long userId, FixedExamCatalog.Paper paper, Long learningSessionId) {
        FixedExamAttempt attempt = new FixedExamAttempt();
        attempt.setUserId(userId);
        attempt.setLearningSessionId(learningSessionId);
        attempt.setPaperCode(paper.code());
        attempt.setCatalogVersion(catalogService.version());
        attempt.setStatus(IN_PROGRESS);
        attempt.setStartedAt(LocalDateTime.now());
        attempt.setTotalDurationSeconds(0);
        attempt.setMaxScore(paper.questions().stream()
                .map(FixedExamCatalog.Question::maxScore)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        return attemptRepository.save(attempt);
    }

    private void ensureAnswerRows(FixedExamAttempt attempt, FixedExamCatalog.Paper paper) {
        Map<String, FixedExamAnswer> existing = new LinkedHashMap<>();
        for (FixedExamAnswer answer : answerRepository.findByAttemptIdOrderByIdAsc(attempt.getId())) {
            existing.put(answer.getQuestionId(), answer);
        }
        List<FixedExamAnswer> missing = paper.questions().stream()
                .filter(question -> !existing.containsKey(question.id()))
                .map(question -> newAnswer(attempt.getId(), question))
                .toList();
        if (!missing.isEmpty()) {
            answerRepository.saveAll(missing);
        }
    }

    private FixedExamAnswer newAnswer(Long attemptId, FixedExamCatalog.Question question) {
        FixedExamAnswer answer = new FixedExamAnswer();
        answer.setAttemptId(attemptId);
        answer.setQuestionId(question.id());
        answer.setUserAnswer("");
        answer.setMaxScore(question.maxScore());
        answer.setCorrect(false);
        answer.setDraft(true);
        answer.setViewedAnswerBeforeSubmit(false);
        answer.setDurationSeconds(0);
        answer.setRevisionCount(0);
        return answer;
    }

    private QuestionResult grade(FixedExamCatalog.Question question, FixedExamAnswer answer) {
        String userAnswer = answer == null ? "" : trim(answer.getUserAnswer(), MAX_ANSWER_CHARS);
        boolean unanswered = userAnswer.isBlank();
        List<ScoringPointResult> pointResults = new ArrayList<>();
        BigDecimal score = BigDecimal.ZERO;
        if ("SINGLE_CHOICE".equals(question.type())) {
            boolean achieved = !unanswered && normalize(userAnswer).equals(normalize(question.standardAnswer()));
            FixedExamCatalog.ScoringPoint point = question.scoringPoints().get(0);
            pointResults.add(new ScoringPointResult(point.id(), point.description(), point.points(), achieved));
            if (achieved) {
                score = question.maxScore();
            }
        } else {
            String normalizedAnswer = normalize(userAnswer);
            for (FixedExamCatalog.ScoringPoint point : question.scoringPoints()) {
                boolean achieved = !unanswered && point.acceptedKeywords().stream()
                        .map(FixedExamAttemptService::normalize)
                        .anyMatch(normalizedAnswer::contains);
                pointResults.add(new ScoringPointResult(point.id(), point.description(), point.points(), achieved));
                if (achieved) {
                    score = score.add(point.points());
                }
            }
        }
        score = score.min(question.maxScore()).setScale(2, RoundingMode.HALF_UP);
        boolean correct = score.compareTo(question.maxScore().setScale(2, RoundingMode.HALF_UP)) == 0;
        boolean reviewRecommended = !"SINGLE_CHOICE".equals(question.type())
                && !unanswered
                && (!correct || userAnswer.length() >= 80);
        return new QuestionResult(
                question.id(),
                question.order(),
                question.prompt(),
                question.type(),
                List.copyOf(question.knowledgePoints()),
                userAnswer,
                score,
                question.maxScore(),
                correct,
                unanswered,
                reviewRecommended,
                answer != null && answer.isViewedAnswerBeforeSubmit(),
                answer == null ? 0 : answer.getDurationSeconds(),
                question.standardAnswer(),
                List.copyOf(pointResults),
                question.explanation(),
                List.copyOf(question.commonErrors()),
                Map.copyOf(question.distractorAnalysis()),
                List.copyOf(question.sources()),
                question.recommendedReview(),
                question.validationMethod(),
                List.copyOf(question.testCases())
        );
    }

    private ExamReportView buildReport(
            Long reportId,
            FixedExamAttempt attempt,
            FixedExamCatalog.Paper paper,
            List<QuestionResult> results,
            BigDecimal totalScore,
            BigDecimal maxScore,
            int answeredCount,
            int correctCount
    ) {
        List<String> viewed = results.stream()
                .filter(QuestionResult::viewedAnswerBeforeSubmit)
                .map(QuestionResult::questionId)
                .toList();
        Set<String> typicalErrors = new LinkedHashSet<>();
        Set<String> recommended = new LinkedHashSet<>();
        for (QuestionResult result : results) {
            if (!result.correct()) {
                if (!result.commonErrors().isEmpty()) {
                    typicalErrors.add(result.commonErrors().get(0));
                }
                recommended.add(result.recommendedReview());
            }
        }
        int totalDuration = results.stream().mapToInt(QuestionResult::durationSeconds).sum();
        int accuracy = paper.questions().isEmpty()
                ? 0
                : (int) Math.round(correctCount * 100.0 / paper.questions().size());
        return new ExamReportView(
                reportId,
                attempt.getId(),
                paper.code(),
                paper.title(),
                totalScore,
                maxScore,
                accuracy,
                totalDuration,
                answeredCount,
                paper.questions().size() - answeredCount,
                List.copyOf(viewed),
                List.copyOf(results),
                buildMastery(results),
                List.copyOf(typicalErrors),
                List.copyOf(recommended),
                attempt.getSubmittedAt()
        );
    }

    private List<MasteryResult> buildMastery(List<QuestionResult> results) {
        Map<String, BigDecimal[]> totals = new LinkedHashMap<>();
        for (QuestionResult result : results) {
            BigDecimal divisor = BigDecimal.valueOf(Math.max(1, result.knowledgePoints().size()));
            BigDecimal earnedShare = result.score().divide(divisor, 4, RoundingMode.HALF_UP);
            BigDecimal maxShare = result.maxScore().divide(divisor, 4, RoundingMode.HALF_UP);
            for (String point : result.knowledgePoints()) {
                BigDecimal[] values = totals.computeIfAbsent(point, ignored -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
                values[0] = values[0].add(earnedShare);
                values[1] = values[1].add(maxShare);
            }
        }
        return totals.entrySet().stream()
                .map(entry -> {
                    BigDecimal earned = entry.getValue()[0].setScale(2, RoundingMode.HALF_UP);
                    BigDecimal maximum = entry.getValue()[1].setScale(2, RoundingMode.HALF_UP);
                    int percent = maximum.signum() == 0
                            ? 0
                            : earned.multiply(BigDecimal.valueOf(100))
                                    .divide(maximum, 0, RoundingMode.HALF_UP)
                                    .intValue();
                    return new MasteryResult(entry.getKey(), earned, maximum, percent);
                })
                .toList();
    }

    private QuestionBankService.AttemptInput toWrongBookInput(
            FixedExamAttempt attempt,
            FixedExamCatalog.Question question,
            QuestionResult result
    ) {
        return new QuestionBankService.AttemptInput(
                attempt.getLearningSessionId(),
                "fixed:" + attempt.getPaperCode() + ":" + question.id(),
                question.prompt(),
                result.userAnswer(),
                question.standardAnswer(),
                question.explanation(),
                String.join("、", question.knowledgePoints()),
                false,
                result.unanswered(),
                "FIXED_EXAM",
                question.id(),
                attempt.getId(),
                result.viewedAnswerBeforeSubmit(),
                result.durationSeconds()
        );
    }

    private AnswerReview toAnswerReview(FixedExamCatalog.Question question) {
        return new AnswerReview(
                question.id(),
                question.standardAnswer(),
                List.copyOf(question.scoringPoints()),
                question.explanation(),
                List.copyOf(question.commonErrors()),
                Map.copyOf(question.distractorAnalysis()),
                List.copyOf(question.sources()),
                question.recommendedReview(),
                question.validationMethod(),
                List.copyOf(question.testCases())
        );
    }

    private AttemptView toAttemptView(FixedExamAttempt attempt) {
        List<AnswerDraft> answers = answerRepository.findByAttemptIdOrderByIdAsc(attempt.getId()).stream()
                .map(this::toAnswerDraft)
                .toList();
        return new AttemptView(
                attempt.getId(),
                attempt.getPaperCode(),
                attempt.getCatalogVersion(),
                attempt.getStatus(),
                attempt.getStartedAt(),
                attempt.getSubmittedAt(),
                attempt.getTotalDurationSeconds(),
                answers
        );
    }

    private AnswerDraft toAnswerDraft(FixedExamAnswer answer) {
        String value = trim(answer.getUserAnswer(), MAX_ANSWER_CHARS);
        return new AnswerDraft(
                answer.getQuestionId(),
                value,
                !value.isBlank(),
                answer.isViewedAnswerBeforeSubmit(),
                answer.getDurationSeconds(),
                answer.getRevisionCount()
        );
    }

    private ExamReportView withReportId(ExamReportView source, Long reportId) {
        return new ExamReportView(
                reportId,
                source.attemptId(),
                source.paperCode(),
                source.paperTitle(),
                source.totalScore(),
                source.maxScore(),
                source.accuracyPercent(),
                source.totalDurationSeconds(),
                source.answeredCount(),
                source.unansweredCount(),
                source.viewedAnswerQuestionIds(),
                source.questions(),
                source.mastery(),
                source.typicalErrors(),
                source.recommendedReviews(),
                source.submittedAt()
        );
    }

    private FixedExamAttempt requireOwnedAttempt(Long userId, Long attemptId) {
        FixedExamAttempt attempt = attemptRepository.findById(attemptId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "题卷作答记录不存在"));
        if (!userId.equals(attempt.getUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "不能访问其他用户的题卷作答");
        }
        return attempt;
    }

    private void requireInProgress(FixedExamAttempt attempt) {
        if (!IN_PROGRESS.equals(attempt.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "题卷已经提交，不能继续修改");
        }
    }

    private void validateOwnedSession(Long userId, Long learningSessionId) {
        if (learningSessionId == null) {
            return;
        }
        LearningSession session = learningSessionRepository.findById(learningSessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "学习会话不存在"));
        if (!userId.equals(session.getUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "学习会话不属于当前用户");
        }
    }

    private void updateAttemptDuration(FixedExamAttempt attempt) {
        int total = answerRepository.findByAttemptIdOrderByIdAsc(attempt.getId()).stream()
                .mapToInt(FixedExamAnswer::getDurationSeconds)
                .sum();
        attempt.setTotalDurationSeconds(total);
        attemptRepository.save(attempt);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize fixed exam data", exception);
        }
    }

    private static int clampDuration(Integer value) {
        return Math.max(0, Math.min(value == null ? 0 : value, 24 * 60 * 60));
    }

    private static String trim(String value, int maxLength) {
        String text = value == null ? "" : value.trim();
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    private static String normalize(String value) {
        return value == null
                ? ""
                : value.toLowerCase(Locale.ROOT)
                        .replaceAll("\\s+", "")
                        .replace('，', ',')
                        .replace('。', '.');
    }
}
