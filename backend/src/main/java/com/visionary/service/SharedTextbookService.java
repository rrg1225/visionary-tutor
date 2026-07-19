package com.visionary.service;

import com.visionary.config.AdminProperties;
import com.visionary.dto.SharedTextbookDto;
import com.visionary.dto.SharedTextbookSubmitRequest;
import com.visionary.entity.SharedTextbook;
import com.visionary.notification.NotificationPublisher;
import com.visionary.notification.NotificationType;
import com.visionary.repository.SharedTextbookRepository;
import com.visionary.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class SharedTextbookService {

    private static final int MAX_CONTENT_LENGTH = 100_000;
    private static final Set<String> ALLOWED_SOURCE_TYPES = Set.of(
            "original", "open_license", "authorized", "personal_notes"
    );

    private final SharedTextbookRepository textbookRepository;
    private final AdminProperties adminProperties;
    private final UserRepository userRepository;
    private final UgcTextbookIndexService ugcTextbookIndexService;
    private final NotificationPublisher notificationPublisher;
    private final AiContentModerationService aiContentModerationService;

    @Transactional(readOnly = true)
    public List<SharedTextbookDto> listPublic() {
        return textbookRepository
                .findByReviewStatusAndVisibilityOrderByGmtCreatedDesc("approved", "public")
                .stream()
                .map(SharedTextbookDto::summary)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<SharedTextbookDto> listMine(Long userId) {
        return textbookRepository.findByOwnerUserIdOrderByGmtCreatedDesc(userId)
                .stream()
                .map(SharedTextbookDto::summary)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<SharedTextbookDto> listPendingForReview() {
        return textbookRepository.findByReviewStatusOrderByGmtCreatedAsc("pending")
                .stream()
                .map(SharedTextbookDto::summary)
                .toList();
    }

    @Transactional(readOnly = true)
    public SharedTextbookDto getById(Long id, Long callerUserId) {
        SharedTextbook book = requireBook(id);
        if (!canRead(book, callerUserId)) {
            throw new IllegalArgumentException("教材未公开或尚未审核通过");
        }
        if (isPublicApproved(book)) {
            book.setViewCount((book.getViewCount() != null ? book.getViewCount() : 0) + 1);
            textbookRepository.save(book);
        }
        return SharedTextbookDto.from(book, true);
    }

    @Transactional(readOnly = true)
    public SharedTextbookDto getForReview(Long id) {
        SharedTextbook book = requireBook(id);
        return SharedTextbookDto.from(book, true);
    }

    @Transactional
    public SharedTextbookDto submit(Long ownerUserId, SharedTextbookSubmitRequest request) {
        if (request.contentMarkdown().length() > MAX_CONTENT_LENGTH) {
            throw new IllegalArgumentException("教材内容过长，请控制在 " + MAX_CONTENT_LENGTH + " 字符以内");
        }
        long headingCount = request.contentMarkdown().lines().filter(line -> line.matches("^#{1,4}\\s+.+")).count();
        if (headingCount < 2) {
            throw new IllegalArgumentException("教材至少需要两个 Markdown 章节标题，并建议包含适用对象、重点知识、学习建议和思考题");
        }
        validateProvenance(request);
        SharedTextbook book = new SharedTextbook();
        book.setOwnerUserId(ownerUserId);
        book.setTitle(request.title().trim());
        book.setDescription(request.description());
        book.setContentMarkdown(request.contentMarkdown().trim());
        book.setSubjectTag(request.subjectTag() != null ? request.subjectTag() : "computer-vision");
        book.setSourceType(request.sourceType().trim());
        book.setSourceTitle(firstNonBlank(request.sourceTitle(), request.title()).trim());
        book.setSourceUrl(trimToNull(request.sourceUrl()));
        book.setLicenseName(trimToNull(request.licenseName()));
        book.setRightsStatement(request.rightsStatement().trim());
        book.setRightsConfirmed(Boolean.TRUE.equals(request.rightsConfirmed()));
        book.setVisibility(request.visibility() != null ? request.visibility() : "public");
        book.setReviewStatus("pending");
        AiContentModerationService.Result aiReview = aiContentModerationService.review(book.getTitle(), book.getContentMarkdown());
        book.setAiReviewStatus(aiReview.status());
        book.setAiRiskLevel(aiReview.riskLevel());
        book.setAiReviewReason(aiReview.reason());
        book.setAiReviewedAt(LocalDateTime.now());
        if ("blocked".equals(aiReview.status())) {
            book.setReviewStatus("rejected");
            book.setRejectionReason("AI 安全审核拦截：" + aiReview.reason());
        }
        book.setViewCount(0);
        return SharedTextbookDto.from(textbookRepository.save(book), true);
    }

    @Transactional
    public SharedTextbookDto approve(Long id, Long reviewerId) {
        requireAdmin(reviewerId);
        SharedTextbook book = requireBook(id);
        if (!"pending".equals(book.getReviewStatus())) {
            throw new IllegalArgumentException("仅待审核教材可通过: " + id);
        }
        if ("blocked".equals(book.getAiReviewStatus())) {
            throw new IllegalArgumentException("AI 安全审核已拦截该教材，不能公开");
        }
        if (!Boolean.TRUE.equals(book.getRightsConfirmed())
                || book.getSourceType() == null
                || book.getSourceType().isBlank()
                || book.getRightsStatement() == null
                || book.getRightsStatement().isBlank()) {
            throw new IllegalArgumentException("来源或版权声明不完整，不能进入知识库");
        }
        book.setReviewStatus("approved");
        book.setReviewedBy(reviewerId);
        book.setReviewedAt(LocalDateTime.now());
        book.setRejectionReason(null);
        SharedTextbook saved = textbookRepository.save(book);

        indexAfterApproval(saved.getId());
        notifyReviewResult(saved, "approved", null);
        return SharedTextbookDto.from(saved, true);
    }

    @Transactional
    public SharedTextbookDto reject(Long id, Long reviewerId, String reason) {
        requireAdmin(reviewerId);
        SharedTextbook book = requireBook(id);
        if (!"pending".equals(book.getReviewStatus())) {
            throw new IllegalArgumentException("仅待审核教材可驳回: " + id);
        }
        book.setReviewStatus("rejected");
        book.setReviewedBy(reviewerId);
        book.setReviewedAt(LocalDateTime.now());
        book.setRejectionReason(reason);
        SharedTextbook saved = textbookRepository.save(book);

        ugcTextbookIndexService.deleteTextbookVectors(id);
        notifyReviewResult(saved, "rejected", reason);
        return SharedTextbookDto.from(saved, true);
    }

    public void requireAdmin(Long userId) {
        boolean admin = adminProperties.isAdmin(userId) || userRepository.findById(userId)
                .map(user -> adminProperties.isAdmin(user.getId(), user.getUsername()))
                .orElse(false);
        if (!admin) {
            throw new IllegalArgumentException("需要管理员权限");
        }
    }

    @Async
    public void indexAfterApproval(Long textbookId) {
        textbookRepository.findById(textbookId).ifPresent(ugcTextbookIndexService::indexTextbook);
    }

    private void notifyReviewResult(SharedTextbook book, String status, String reason) {
        notificationPublisher.publish(
                book.getOwnerUserId(),
                NotificationType.UGC_REVIEW_RESULT,
                Map.of(
                        "textbookId", book.getId(),
                        "title", book.getTitle(),
                        "status", status,
                        "reason", reason == null ? "" : reason
                )
        );
    }

    private SharedTextbook requireBook(Long id) {
        return textbookRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("教材不存在: " + id));
    }

    private boolean canRead(SharedTextbook book, Long callerUserId) {
        if (isPublicApproved(book)) {
            return true;
        }
        return callerUserId != null && callerUserId.equals(book.getOwnerUserId());
    }

    private boolean isPublicApproved(SharedTextbook book) {
        return "approved".equals(book.getReviewStatus()) && "public".equals(book.getVisibility());
    }

    private static void validateProvenance(SharedTextbookSubmitRequest request) {
        String sourceType = request.sourceType() == null ? "" : request.sourceType().trim();
        if (!ALLOWED_SOURCE_TYPES.contains(sourceType)) {
            throw new IllegalArgumentException("请选择有效的教材来源类型");
        }
        if (!Boolean.TRUE.equals(request.rightsConfirmed())) {
            throw new IllegalArgumentException("请确认你有权提交该材料");
        }
        if (!"original".equals(sourceType)
                && (request.sourceTitle() == null || request.sourceTitle().isBlank())) {
            throw new IllegalArgumentException("非原创材料必须填写原始资料名称");
        }
        if (Set.of("open_license", "authorized").contains(sourceType)
                && (request.licenseName() == null || request.licenseName().isBlank())) {
            throw new IllegalArgumentException("开放许可或已获授权材料必须填写许可/授权方式");
        }
    }

    private static String firstNonBlank(String first, String fallback) {
        return first != null && !first.isBlank() ? first : fallback;
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
