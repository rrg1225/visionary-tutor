package com.visionary.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * OSS 存储配置类。
 * 支持阿里云 OSS 或 MinIO 配置。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "aliyun.oss")
public class OssConfig {

    /**
     * 是否启用 OSS 上传功能。
     */
    private boolean enabled = true;

    /**
     * 存储类型：aliyun-oss 或 minio。
     */
    private String type = "aliyun-oss";

    /**
     * Endpoint 地址。
     * 阿里云示例：https://oss-cn-beijing.aliyuncs.com
     * MinIO 示例：http://localhost:9000
     */
    private String endpoint;

    /**
     * Access Key ID。
     */
    private String accessKeyId;

    /**
     * Access Key Secret。
     */
    private String accessKeySecret;

    /**
     * 存储桶名称。
     */
    private String bucketName;

    /**
     * 自定义域名（可选，用于生成更友好的 URL）。
     */
    private String customDomain;

    /**
     * 文件过期时间（秒），默认 1 小时。
     */
    private int urlExpirationSeconds = 3600;

    /**
     * 上传文件大小限制（MB），默认 10MB。
     */
    private int maxFileSizeMb = 10;

    /**
     * 允许的文件类型，默认图片格式。
     */
    private String allowedContentTypes = "image/jpeg,image/png,image/gif,image/webp";

    public boolean isConfigured() {
        return enabled
                && endpoint != null && !endpoint.isBlank()
                && accessKeyId != null && !accessKeyId.isBlank()
                && accessKeySecret != null && !accessKeySecret.isBlank()
                && bucketName != null && !bucketName.isBlank();
    }

    public boolean isAliyunOss() {
        return "aliyun-oss".equalsIgnoreCase(type);
    }

    public boolean isMinio() {
        return "minio".equalsIgnoreCase(type);
    }
}
