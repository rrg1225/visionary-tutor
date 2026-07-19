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
@Table(name = "session_chat_message")
public class SessionChatMessage extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "learning_session_id", nullable = false)
    private Long learningSessionId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "role", nullable = false, length = 16)
    private String role;

    @Column(name = "context_type", nullable = false, length = 32)
    private String contextType = "GENERAL";

    @Column(name = "context_key", nullable = false, length = 160)
    private String contextKey = "";

    @Column(name = "context_title", length = 255)
    private String contextTitle;

    @Column(name = "content", nullable = false, columnDefinition = "LONGTEXT")
    private String content;

    @Column(name = "metadata_json", columnDefinition = "LONGTEXT")
    private String metadataJson;

    @Column(name = "seq", nullable = false)
    private Integer seq;
}
