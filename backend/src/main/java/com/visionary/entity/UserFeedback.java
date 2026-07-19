package com.visionary.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "user_feedback")
public class UserFeedback extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "category", nullable = false, length = 32)
    private String category;

    @Column(name = "message", nullable = false, length = 2000)
    private String message;

    @Column(name = "contact", length = 128)
    private String contact;

    @Column(name = "page_path", length = 256)
    private String pagePath;

    @Column(name = "status", nullable = false, length = 24)
    private String status = "PENDING";
}
