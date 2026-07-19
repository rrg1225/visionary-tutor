package com.visionary.controller;

import com.visionary.entity.ContentReleaseReview;
import com.visionary.repository.ContentReleaseReviewRepository;
import com.visionary.security.CustomUserDetails;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/ops/content-reviews")
@RequiredArgsConstructor
public class ContentReleaseReviewController {
    private static final Set<String> TYPES = Set.of("FIXED_EXAM", "SYSTEM_KNOWLEDGE");
    private static final Set<String> DECISIONS = Set.of("APPROVED", "REJECTED");
    private final ContentReleaseReviewRepository repository;

    @GetMapping
    public Map<String, Object> status(@RequestParam String contentType, @RequestParam String contentVersion) {
        List<ContentReleaseReview> rows = repository.findByContentTypeAndContentVersionOrderByReviewedAtAsc(normalizeType(contentType), contentVersion.trim());
        long approvals = rows.stream().filter(row -> "APPROVED".equals(row.getDecision())).map(ContentReleaseReview::getReviewerId).distinct().count();
        boolean rejected = rows.stream().anyMatch(row -> "REJECTED".equals(row.getDecision()));
        return Map.of("ready", approvals >= 2 && !rejected, "approvalCount", approvals, "rejected", rejected, "reviews", rows);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ContentReleaseReview sign(@Valid @RequestBody ReviewRequest request, Authentication authentication) {
        Long reviewerId = requireUserId(authentication);
        String type = normalizeType(request.contentType());
        String version = request.contentVersion().trim();
        if (repository.existsByContentTypeAndContentVersionAndReviewerId(type, version, reviewerId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "同一审核人不能重复签字");
        }
        String decision = request.decision().trim().toUpperCase(Locale.ROOT);
        if (!DECISIONS.contains(decision)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "审核结论无效");
        ContentReleaseReview review = new ContentReleaseReview();
        review.setContentType(type);
        review.setContentVersion(version);
        review.setReviewerId(reviewerId);
        review.setReviewerRole(request.reviewerRole().trim());
        review.setDecision(decision);
        review.setNotes(request.notes().trim());
        return repository.save(review);
    }

    private static String normalizeType(String value) {
        String type = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!TYPES.contains(type)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "内容类型无效");
        return type;
    }

    private static Long requireUserId(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof CustomUserDetails details && details.isRegisteredUser()) return details.getUserId();
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "需要管理员登录");
    }

    public record ReviewRequest(@NotBlank String contentType, @NotBlank @Size(max = 64) String contentVersion,
                                @NotBlank @Size(max = 32) String reviewerRole, @NotBlank String decision,
                                @NotBlank @Size(max = 2000) String notes) {}
}
