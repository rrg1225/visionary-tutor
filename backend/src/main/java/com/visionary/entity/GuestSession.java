package com.visionary.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * Guest Session Entity - 管理游客的临时会话数据
 * 
 * 设计原则：
 * 1. 游客ID使用 UUID 格式，前缀 "gst_" 以便与正式用户区分
 * 2. context_json 存储游客的对话上下文、学习偏好等临时数据
 * 3. expires_at 控制游客会话生命周期（默认7天）
 * 4. converted_user_id 记录该游客最终转换的正式用户（用于审计）
 * 
 * 数据库索引：
 * - idx_converted_user_id：快速查询某正式用户的前身游客
 * - idx_expires_at：定时清理过期游客数据
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(
    name = "guest_session",
    indexes = {
        @Index(name = "idx_converted_user_id", columnList = "converted_user_id"),
        @Index(name = "idx_expires_at", columnList = "expires_at")
    }
)
public class GuestSession extends BaseEntity {

    /**
     * 游客唯一标识，格式：gst_{uuid}
     * 示例：gst_a1b2c3d4-e5f6-7890-abcd-ef1234567890
     */
    @Id
    @Column(name = "guest_id", length = 64, nullable = false)
    private String guestId;

    /**
     * 游客会话上下文（JSON格式存储）
     * 包含：对话历史、学习偏好、临时选择等
     */
    @Column(name = "context_json", columnDefinition = "LONGTEXT")
    private String contextJson;

    /**
     * 会话过期时间
     * 默认创建后7天过期，避免长期占用存储
     */
    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    /**
     * 转换为正式用户后的用户ID
     * 用于数据审计和追踪用户转化路径
     */
    @Column(name = "converted_user_id")
    private Long convertedUserId;

    @Column(name = "migration_status", nullable = false, length = 24)
    private String migrationStatus = "PENDING";

    @Column(name = "migration_error", length = 512)
    private String migrationError;

    @Column(name = "migrated_at")
    private LocalDateTime migratedAt;

    @Column(name = "migrated_session_id")
    private Long migratedSessionId;

    /**
     * 客户端指纹（可选）
     * 用于识别同一设备的重复游客
     */
    @Column(name = "device_fingerprint", length = 256)
    private String deviceFingerprint;

    /**
     * IP地址（可选，用于安全审计）
     */
    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    /**
     * 检查会话是否已过期
     */
    public boolean isExpired() {
        return LocalDateTime.now().isAfter(this.expiresAt);
    }

    /**
     * 检查是否已转换为正式用户
     */
    public boolean isConverted() {
        return this.convertedUserId != null;
    }
}
