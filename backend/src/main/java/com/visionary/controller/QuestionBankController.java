package com.visionary.controller;

import com.visionary.entity.QuestionAttempt;
import com.visionary.security.AuthContext;
import com.visionary.service.QuestionBankService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/questions")
@RequiredArgsConstructor
public class QuestionBankController {

    private final QuestionBankService questionBankService;

    @PostMapping("/attempts")
    public List<QuestionAttempt> recordAttempts(@RequestBody AttemptBatchRequest request) {
        return questionBankService.recordAttempts(requireUserId(), request.attempts());
    }

    @GetMapping("/wrong-book")
    public List<QuestionAttempt> wrongBook() {
        return questionBankService.wrongBook(requireUserId());
    }

    @GetMapping("/reviews/due")
    public List<QuestionAttempt> dueReviews() {
        return questionBankService.dueReviews(requireUserId());
    }

    @PutMapping("/attempts/{attemptId}/review")
    public QuestionAttempt review(@PathVariable Long attemptId, @RequestBody ReviewRequest request) {
        return questionBankService.review(requireUserId(), attemptId, request.correct());
    }

    private static Long requireUserId() {
        return AuthContext.currentRegisteredUserId()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录后才能使用题库和错题本"));
    }

    public record AttemptBatchRequest(List<QuestionBankService.AttemptInput> attempts) {
    }

    public record ReviewRequest(boolean correct) {
    }
}
