package com.visionary.controller;

import com.visionary.exam.FixedExamAttemptService;
import com.visionary.exam.FixedExamCatalogService;
import com.visionary.exam.FixedExamDtos.AnswerDraft;
import com.visionary.exam.FixedExamDtos.AnswerReview;
import com.visionary.exam.FixedExamDtos.AttemptView;
import com.visionary.exam.FixedExamDtos.ExamReportView;
import com.visionary.exam.FixedExamDtos.PaperSummary;
import com.visionary.exam.FixedExamDtos.PaperView;
import com.visionary.exam.FixedExamDtos.SaveAnswerRequest;
import com.visionary.exam.FixedExamDtos.StartAttemptRequest;
import com.visionary.security.AuthContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/fixed-exams")
@RequiredArgsConstructor
public class FixedExamController {

    private final FixedExamCatalogService catalogService;
    private final FixedExamAttemptService attemptService;

    @GetMapping
    public List<PaperSummary> listPapers() {
        requireUserId();
        return catalogService.listPapers();
    }

    @GetMapping("/reports")
    public List<ExamReportView> reports() {
        return attemptService.listReports(requireUserId());
    }

    @GetMapping("/{paperCode}")
    public PaperView paper(@PathVariable String paperCode) {
        requireUserId();
        return catalogService.paperView(paperCode);
    }

    @PostMapping("/{paperCode}/attempts")
    public AttemptView startAttempt(
            @PathVariable String paperCode,
            @RequestBody(required = false) StartAttemptRequest request
    ) {
        Long learningSessionId = request == null ? null : request.learningSessionId();
        return attemptService.startAttempt(requireUserId(), paperCode, learningSessionId);
    }

    @GetMapping("/attempts/{attemptId}")
    public AttemptView attempt(@PathVariable Long attemptId) {
        return attemptService.getAttempt(requireUserId(), attemptId);
    }

    @PutMapping("/attempts/{attemptId}/answers/{questionId}")
    public AnswerDraft saveAnswer(
            @PathVariable Long attemptId,
            @PathVariable String questionId,
            @RequestBody SaveAnswerRequest request
    ) {
        return attemptService.saveAnswer(requireUserId(), attemptId, questionId, request);
    }

    @PostMapping("/attempts/{attemptId}/answers/{questionId}/reveal")
    public AnswerReview revealAnswer(@PathVariable Long attemptId, @PathVariable String questionId) {
        return attemptService.revealAnswer(requireUserId(), attemptId, questionId);
    }

    @PostMapping("/attempts/{attemptId}/submit")
    public ExamReportView submit(@PathVariable Long attemptId) {
        return attemptService.submit(requireUserId(), attemptId);
    }

    @GetMapping("/attempts/{attemptId}/report")
    public ExamReportView report(@PathVariable Long attemptId) {
        return attemptService.getReport(requireUserId(), attemptId);
    }

    private static Long requireUserId() {
        return AuthContext.currentRegisteredUserId()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录后才能使用固定题卷"));
    }
}
