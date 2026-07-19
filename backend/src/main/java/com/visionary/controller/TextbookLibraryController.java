package com.visionary.controller;

import com.visionary.dto.RecommendationPushDto;
import com.visionary.dto.SharedTextbookDto;
import com.visionary.dto.SharedTextbookRejectRequest;
import com.visionary.dto.SharedTextbookSubmitRequest;
import com.visionary.config.AdminProperties;
import com.visionary.security.CustomUserDetails;
import com.visionary.service.RecommendationPushService;
import com.visionary.service.SharedTextbookService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/library")
@RequiredArgsConstructor
public class TextbookLibraryController {

    private final SharedTextbookService textbookService;
    private final RecommendationPushService recommendationPushService;
    private final AdminProperties adminProperties;

    @GetMapping("/textbooks")
    public List<SharedTextbookDto> listPublic() {
        return textbookService.listPublic();
    }

    @GetMapping("/textbooks/mine")
    public List<SharedTextbookDto> listMine() {
        Long userId = requireRegisteredUserId();
        return textbookService.listMine(userId);
    }

    @GetMapping("/textbooks/{id}")
    public SharedTextbookDto get(@PathVariable Long id) {
        Long callerUserId = resolveCallerUserId().orElse(null);
        return textbookService.getById(id, callerUserId);
    }

    @PostMapping("/textbooks")
    public SharedTextbookDto submit(@Valid @RequestBody SharedTextbookSubmitRequest request) {
        Long userId = requireRegisteredUserId();
        return textbookService.submit(userId, request);
    }

    @GetMapping("/admin/status")
    public java.util.Map<String, Boolean> adminStatus() {
        Long userId = requireRegisteredUserId();
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String username = authentication != null ? authentication.getName() : null;
        return java.util.Map.of("admin", adminProperties.isAdmin(userId, username));
    }

    @GetMapping("/admin/textbooks/pending")
    @PreAuthorize("hasRole('ADMIN')")
    public List<SharedTextbookDto> listPending() {
        return textbookService.listPendingForReview();
    }

    @GetMapping("/admin/textbooks/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public SharedTextbookDto getForReview(@PathVariable Long id) {
        return textbookService.getForReview(id);
    }

    @PostMapping("/admin/textbooks/{id}/approve")
    @PreAuthorize("hasRole('ADMIN')")
    public SharedTextbookDto approve(@PathVariable Long id) {
        return textbookService.approve(id, requireRegisteredUserId());
    }

    @PostMapping("/admin/textbooks/{id}/reject")
    @PreAuthorize("hasRole('ADMIN')")
    public SharedTextbookDto reject(@PathVariable Long id, @Valid @RequestBody SharedTextbookRejectRequest request) {
        return textbookService.reject(id, requireRegisteredUserId(), request.reason());
    }

    @GetMapping("/recommendations/pending")
    public RecommendationPushDto pendingPush() {
        Long userId = requireRegisteredUserId();
        return recommendationPushService.getPendingPush(userId)
                .orElse(new RecommendationPushDto(null, null, null, null, List.of()));
    }

    @PostMapping("/recommendations/push/{pushId}/consume")
    public void consumePush(@PathVariable Long pushId) {
        Long userId = requireRegisteredUserId();
        recommendationPushService.markConsumed(pushId, userId);
    }

    private static Long requireRegisteredUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "需要登录");
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof CustomUserDetails details && details.isRegisteredUser()) {
            return details.getUserId();
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要注册账号");
    }

    private static java.util.Optional<Long> resolveCallerUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return java.util.Optional.empty();
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof CustomUserDetails details && details.isRegisteredUser()) {
            return java.util.Optional.ofNullable(details.getUserId());
        }
        return java.util.Optional.empty();
    }
}
