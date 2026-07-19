package com.visionary.repository;

import com.visionary.entity.ContentReleaseReview;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ContentReleaseReviewRepository extends JpaRepository<ContentReleaseReview, Long> {
    List<ContentReleaseReview> findByContentTypeAndContentVersionOrderByReviewedAtAsc(String contentType, String contentVersion);
    boolean existsByContentTypeAndContentVersionAndReviewerId(String contentType, String contentVersion, Long reviewerId);
}
