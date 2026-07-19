package com.visionary.util;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.ObjectMetadata;
import com.visionary.config.OssConfig;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Date;
import java.util.Set;
import java.util.UUID;

/**
 * OSS 文件上传工具类。
 * <p>
 * 优雅的单例实现：Spring 容器管理实例生命周期，
 * 提供静态便捷方法 {@link #upload(MultipartFile)} 和 {@link #upload(MultipartFile, String)}。
 * </p>
 *
 * <p>使用示例：</p>
 * <pre>
 *   // 方式一：依赖注入（推荐在 Service/Controller 中使用）
 *   @Autowired
 *   private OssUtil ossUtil;
 *   String url = ossUtil.upload(file);
 *
 *   // 方式二：静态方法（便捷但不推荐，失去可测试性）
 *   String url = OssUtil.upload(file);
 * </pre>
 */
@Slf4j
@Component
public class OssUtil {

    private static final Set<String> ALLOWED_IMAGE_TYPES = Set.of(
            "image/jpeg", "image/png", "image/gif", "image/webp", "image/bmp"
    );

    private final OssConfig config;
    private volatile OSS ossClient;
    private volatile boolean initialized = false;

    // 单例实例（由 Spring 容器管理）
    private static OssUtil instance;

    public OssUtil(OssConfig config) {
        this.config = config;
    }

    /**
     * 初始化 OSS 客户端。
     */
    @PostConstruct
    public synchronized void initialize() {
        if (initialized || !config.isConfigured()) {
            if (!config.isConfigured()) {
                log.warn("OSS 未配置或配置不完整，OssUtil 将以不可用状态运行");
            }
            return;
        }

        try {
            this.ossClient = new OSSClientBuilder().build(
                    config.getEndpoint(),
                    config.getAccessKeyId(),
                    config.getAccessKeySecret()
            );
            initialized = true;
            instance = this;
            log.info("OssUtil 初始化成功，Bucket: {}", config.getBucketName());
        } catch (Exception e) {
            log.error("OssUtil 初始化失败: {}", e.getMessage(), e);
            throw new IllegalStateException("OSS 客户端初始化失败", e);
        }
    }

    /**
     * 关闭 OSS 客户端。
     */
    @PreDestroy
    public synchronized void destroy() {
        if (ossClient != null) {
            ossClient.shutdown();
            log.info("OssUtil 已关闭");
        }
        initialized = false;
        instance = null;
    }

    /**
     * 检查 OSS 是否已配置并可用。
     */
    public boolean isAvailable() {
        return initialized && ossClient != null;
    }

    /**
     * 上传文件到 OSS，返回公网可访问的 URL。
     *
     * @param file 待上传的文件
     * @return 文件访问 URL
     * @throws IOException 上传失败或文件校验不通过
     */
    public String upload(MultipartFile file) throws IOException {
        return upload(file, generateUniqueFileName(file));
    }

    /**
     * 上传文件到 OSS，指定文件名，返回公网可访问的 URL。
     *
     * @param file     待上传的文件
     * @param fileName 指定的文件名（如：assessment/12345/draft.png）
     * @return 文件访问 URL
     * @throws IOException 上传失败或文件校验不通过
     */
    public String upload(MultipartFile file, String fileName) throws IOException {
        validateFile(file);

        if (!isAvailable()) {
            throw new IOException("OSS 服务不可用，请检查配置");
        }

        try (InputStream inputStream = file.getInputStream()) {
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentType(file.getContentType());
            metadata.setContentLength(file.getSize());

            // 上传文件
            ossClient.putObject(config.getBucketName(), fileName, inputStream, metadata);

            // 生成公网访问 URL
            String fileUrl = generateFileUrl(fileName);

            log.info("文件上传成功: {} -> {}", file.getOriginalFilename(), fileUrl);
            return fileUrl;

        } catch (Exception e) {
            log.error("文件上传失败: {}", e.getMessage(), e);
            throw new IOException("文件上传到 OSS 失败: " + e.getMessage(), e);
        }
    }

    public String upload(InputStream inputStream, String fileName, String contentType, long contentLength) throws IOException {
        if (inputStream == null) {
            throw new IOException("上传内容不能为空");
        }
        if (fileName == null || fileName.isBlank()) {
            throw new IOException("OSS 文件名不能为空");
        }
        if (!isAvailable()) {
            throw new IOException("OSS 服务不可用，请检查配置");
        }

        try {
            ObjectMetadata metadata = new ObjectMetadata();
            if (contentType != null && !contentType.isBlank()) {
                metadata.setContentType(contentType);
            }
            if (contentLength >= 0) {
                metadata.setContentLength(contentLength);
            }
            ossClient.putObject(config.getBucketName(), fileName, inputStream, metadata);
            String fileUrl = generateFileUrl(fileName);
            log.info("流式文件上传成功: {}", fileUrl);
            return fileUrl;
        } catch (Exception e) {
            log.error("流式文件上传失败: {}", e.getMessage(), e);
            throw new IOException("文件上传到 OSS 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 生成带签名的临时访问 URL（适用于私有 Bucket）。
     *
     * @param fileName   文件名
     * @param expiration 过期时间（秒）
     * @return 带签名的临时 URL
     */
    public URL generatePresignedUrl(String fileName, int expiration) {
        if (!isAvailable()) {
            throw new IllegalStateException("OSS 服务不可用");
        }

        Date expirationDate = new Date(System.currentTimeMillis() + expiration * 1000L);
        return ossClient.generatePresignedUrl(config.getBucketName(), fileName, expirationDate);
    }

    /**
     * 删除 OSS 文件。
     *
     * @param fileName 文件名
     */
    public void delete(String fileName) {
        if (!isAvailable()) {
            log.warn("OSS 不可用，无法删除文件: {}", fileName);
            return;
        }

        try {
            ossClient.deleteObject(config.getBucketName(), fileName);
            log.info("文件删除成功: {}", fileName);
        } catch (Exception e) {
            log.error("文件删除失败: {}", e.getMessage(), e);
        }
    }

    // ==================== 静态便捷方法（内部委托给单例实例） ====================

    /**
     * 静态上传方法（便捷但不推荐，失去可测试性）。
     * <p>优先使用依赖注入方式调用实例方法。</p>
     *
     * @param file 待上传的文件
     * @return 文件访问 URL
     * @throws IOException 上传失败
     */
    public static String quickUpload(MultipartFile file) throws IOException {
        if (instance == null || !instance.isAvailable()) {
            throw new IOException("OssUtil 未初始化或不可用，请检查 OSS 配置");
        }
        return instance.upload(file);
    }

    /**
     * 静态上传方法，指定文件名。
     *
     * @param file     待上传的文件
     * @param fileName 指定的文件名
     * @return 文件访问 URL
     * @throws IOException 上传失败
     */
    public static String quickUpload(MultipartFile file, String fileName) throws IOException {
        if (instance == null || !instance.isAvailable()) {
            throw new IOException("OssUtil 未初始化或不可用，请检查 OSS 配置");
        }
        return instance.upload(file, fileName);
    }

    // ==================== 私有辅助方法 ====================

    private void validateFile(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IOException("上传文件不能为空");
        }

        // 校验文件大小
        long maxSizeBytes = (long) config.getMaxFileSizeMb() * 1024 * 1024;
        if (file.getSize() > maxSizeBytes) {
            throw new IOException(
                    String.format("文件大小超过限制: %dMB > %dMB",
                            file.getSize() / (1024 * 1024),
                            config.getMaxFileSizeMb())
            );
        }

        // 校验文件类型
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_IMAGE_TYPES.contains(contentType.toLowerCase())) {
            throw new IOException("不支持的文件类型: " + contentType + "，仅支持: " + ALLOWED_IMAGE_TYPES);
        }
    }

    private String generateUniqueFileName(MultipartFile file) {
        String originalFilename = file.getOriginalFilename();
        String extension = "";
        if (originalFilename != null && originalFilename.contains(".")) {
            extension = originalFilename.substring(originalFilename.lastIndexOf("."));
        }

        // 路径格式：visionary-tutor/assessment/2025/05/26/uuid.png
        String datePath = java.time.LocalDate.now().toString().replace("-", "/");
        return String.format("visionary-tutor/assessment/%s/%s%s",
                datePath, UUID.randomUUID().toString().replace("-", ""), extension);
    }

    private String generateFileUrl(String fileName) {
        // 如果使用自定义域名
        if (config.getCustomDomain() != null && !config.getCustomDomain().isBlank()) {
            return config.getCustomDomain() + "/" + fileName;
        }

        // 否则使用默认的 OSS 访问地址
        // 格式：https://bucket-name.endpoint/file-name
        String endpoint = config.getEndpoint();
        if (endpoint.startsWith("https://")) {
            endpoint = endpoint.substring(8);
        } else if (endpoint.startsWith("http://")) {
            endpoint = endpoint.substring(7);
        }

        return String.format("https://%s.%s/%s", config.getBucketName(), endpoint, fileName);
    }
}
