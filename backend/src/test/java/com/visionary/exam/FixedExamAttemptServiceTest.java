package com.visionary.exam;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.visionary.entity.FixedExamAnswer;
import com.visionary.entity.FixedExamAttempt;
import com.visionary.entity.FixedExamReport;
import com.visionary.repository.FixedExamAnswerRepository;
import com.visionary.repository.FixedExamAttemptRepository;
import com.visionary.repository.FixedExamReportRepository;
import com.visionary.repository.LearningSessionRepository;
import com.visionary.service.QuestionBankService;
import com.visionary.service.LearningEvidenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FixedExamAttemptServiceTest {

    private static final Long USER_ID = 7L;
    private static final Long ATTEMPT_ID = 55L;
    private static final String PAPER_CODE = "cnn-convolution-v1";

    @Mock
    private FixedExamAttemptRepository attemptRepository;
    @Mock
    private FixedExamAnswerRepository answerRepository;
    @Mock
    private FixedExamReportRepository reportRepository;
    @Mock
    private LearningSessionRepository learningSessionRepository;
    @Mock
    private QuestionBankService questionBankService;
    @Mock
    private LearningEvidenceService learningEvidenceService;

    private FixedExamCatalogService catalogService;
    private FixedExamAttemptService service;
    private FixedExamAttempt attempt;
    private List<FixedExamAnswer> answers;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        catalogService = new FixedExamCatalogService(objectMapper);
        catalogService.initialize();
        service = new FixedExamAttemptService(
                catalogService,
                attemptRepository,
                answerRepository,
                reportRepository,
                learningSessionRepository,
                questionBankService,
                objectMapper,
                learningEvidenceService
        );
        attempt = attempt();
        answers = perfectAnswers();
        when(attemptRepository.findById(ATTEMPT_ID)).thenReturn(Optional.of(attempt));
        when(attemptRepository.save(any(FixedExamAttempt.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(answerRepository.findByAttemptIdOrderByIdAsc(ATTEMPT_ID)).thenAnswer(ignored -> answers);
        when(answerRepository.save(any(FixedExamAnswer.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(reportRepository.save(any(FixedExamReport.class))).thenAnswer(invocation -> {
            FixedExamReport report = invocation.getArgument(0);
            if (report.getId() == null) {
                report.setId(99L);
            }
            return report;
        });
    }

    @Test
    void submitProducesDeterministicFullScoreWithoutWrongBookEntries() {
        answers.get(0).setViewedAnswerBeforeSubmit(true);

        FixedExamDtos.ExamReportView report = service.submit(USER_ID, ATTEMPT_ID);

        assertEquals(0, new BigDecimal("100").compareTo(report.totalScore()));
        assertEquals(100, report.accuracyPercent());
        assertEquals(8, report.answeredCount());
        assertEquals(List.of("cnn-q1"), report.viewedAnswerQuestionIds());
        assertTrue(report.questions().stream().allMatch(FixedExamDtos.QuestionResult::correct));
        assertEquals("SUBMITTED", attempt.getStatus());
        assertEquals(99L, report.reportId());
        verify(questionBankService, never()).recordAttempts(any(), any());
    }

    @Test
    void submitLinksWrongAndUnansweredQuestionToWrongBookWithAttemptMetadata() {
        FixedExamAnswer unanswered = answers.get(0);
        unanswered.setUserAnswer("");
        unanswered.setViewedAnswerBeforeSubmit(true);
        unanswered.setDurationSeconds(41);

        FixedExamDtos.ExamReportView report = service.submit(USER_ID, ATTEMPT_ID);

        assertEquals(7, report.answeredCount());
        assertEquals(1, report.unansweredCount());
        assertEquals(88, report.accuracyPercent());
        FixedExamDtos.QuestionResult first = report.questions().get(0);
        assertTrue(first.unanswered());
        assertFalse(first.correct());
        assertTrue(first.viewedAnswerBeforeSubmit());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<QuestionBankService.AttemptInput>> captor = ArgumentCaptor.forClass(List.class);
        verify(questionBankService).recordAttempts(org.mockito.ArgumentMatchers.eq(USER_ID), captor.capture());
        assertEquals(1, captor.getValue().size());
        QuestionBankService.AttemptInput wrongBook = captor.getValue().get(0);
        assertEquals("FIXED_EXAM", wrongBook.sourceType());
        assertEquals("cnn-q1", wrongBook.sourceQuestionId());
        assertEquals(ATTEMPT_ID, wrongBook.fixedExamAttemptId());
        assertTrue(wrongBook.skipped());
        assertTrue(wrongBook.viewedAnswerBeforeSubmit());
        assertEquals(41, wrongBook.durationSeconds());
    }

    private FixedExamAttempt attempt() {
        FixedExamAttempt value = new FixedExamAttempt();
        value.setId(ATTEMPT_ID);
        value.setUserId(USER_ID);
        value.setPaperCode(PAPER_CODE);
        value.setCatalogVersion("1.0.0");
        value.setStatus("IN_PROGRESS");
        value.setStartedAt(LocalDateTime.now().minusMinutes(10));
        value.setMaxScore(new BigDecimal("100"));
        value.setTotalDurationSeconds(0);
        return value;
    }

    private List<FixedExamAnswer> perfectAnswers() {
        List<FixedExamAnswer> values = new ArrayList<>();
        for (FixedExamCatalog.Question question : catalogService.requirePaper(PAPER_CODE).questions()) {
            FixedExamAnswer answer = new FixedExamAnswer();
            answer.setAttemptId(ATTEMPT_ID);
            answer.setQuestionId(question.id());
            answer.setUserAnswer(perfectAnswer(question));
            answer.setMaxScore(question.maxScore());
            answer.setCorrect(false);
            answer.setDraft(true);
            answer.setDurationSeconds(30);
            values.add(answer);
        }
        return values;
    }

    private String perfectAnswer(FixedExamCatalog.Question question) {
        if ("SINGLE_CHOICE".equals(question.type())) {
            return question.standardAnswer();
        }
        return question.scoringPoints().stream()
                .map(point -> point.acceptedKeywords().get(0))
                .reduce((left, right) -> left + "；" + right)
                .orElseThrow();
    }
}
