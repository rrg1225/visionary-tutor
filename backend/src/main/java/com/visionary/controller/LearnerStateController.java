package com.visionary.controller;

import com.visionary.dto.LearnerStateResponse;
import com.visionary.dto.LearningOsEventDto;
import com.visionary.dto.RecommendationPushDto;
import com.visionary.os.LearnerStateStore;
import com.visionary.os.LearningEventBus;
import com.visionary.service.RecommendationPushService;
import com.visionary.security.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/learner")
@RequiredArgsConstructor
public class LearnerStateController {

    private final LearnerStateStore learnerStateStore;
    private final LearningEventBus learningEventBus;
    private final RecommendationPushService recommendationPushService;

    @GetMapping("/state")
    public LearnerStateResponse state() {
        Long userId = resolveAuthenticatedUserId()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "需要登录后访问学习者状态"));
        var view = learnerStateStore.getState(userId);
        List<LearningOsEventDto> events = learningEventBus.recentEvents(userId).stream()
                .map(LearningOsEventDto::from)
                .toList();
        RecommendationPushDto pending = recommendationPushService.getPendingPush(userId)
                .orElse(new RecommendationPushDto(null, null, null, null, List.of()));
        return LearnerStateResponse.from(view, events, pending);
    }

    private static java.util.Optional<Long> resolveAuthenticatedUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return java.util.Optional.empty();
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof CustomUserDetails details && details.isRegisteredUser()) {
            return java.util.Optional.of(details.getUserId());
        }
        return java.util.Optional.empty();
    }
}
