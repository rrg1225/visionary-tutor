package com.visionary.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "content_release_review")
@Getter
@Setter
public class ContentReleaseReview {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "content_type", nullable = false, length = 32) private String contentType;
    @Column(name = "content_version", nullable = false, length = 64) private String contentVersion;
    @Column(name = "reviewer_id", nullable = false) private Long reviewerId;
    @Column(name = "reviewer_role", nullable = false, length = 32) private String reviewerRole;
    @Column(nullable = false, length = 16) private String decision;
    @Column(nullable = false, length = 2000) private String notes;
    @Column(name = "reviewed_at", nullable = false, updatable = false) private LocalDateTime reviewedAt;
    @PrePersist void prePersist() { if (reviewedAt == null) reviewedAt = LocalDateTime.now(); }
}
