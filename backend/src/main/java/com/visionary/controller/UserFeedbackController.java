package com.visionary.controller;

import com.visionary.dto.UserFeedbackRequest;
import com.visionary.entity.UserFeedback;
import com.visionary.repository.UserFeedbackRepository;
import com.visionary.security.AuthContext;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/feedback")
@RequiredArgsConstructor
public class UserFeedbackController {

    private final UserFeedbackRepository feedbackRepository;

    @PostMapping
    public ResponseEntity<UserFeedback> submit(@Valid @RequestBody UserFeedbackRequest request) {
        UserFeedback feedback = new UserFeedback();
        feedback.setUserId(requireUserId());
        feedback.setCategory(request.category().trim());
        feedback.setMessage(request.message().trim());
        feedback.setContact(trimToNull(request.contact()));
        feedback.setPagePath(trimToNull(request.pagePath()));
        return ResponseEntity.status(HttpStatus.CREATED).body(feedbackRepository.save(feedback));
    }

    @GetMapping("/mine")
    public List<UserFeedback> mine() {
        return feedbackRepository.findByUserIdOrderByGmtCreatedDesc(requireUserId());
    }

    private static Long requireUserId() {
        return AuthContext.currentRegisteredUserId()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请登录后提交反馈"));
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
