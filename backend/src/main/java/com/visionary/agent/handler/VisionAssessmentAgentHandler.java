package com.visionary.agent.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.visionary.agent.AgentJsonParser;
import com.visionary.agent.AgentResponse;
import com.visionary.agent.AgentService;
import com.visionary.agent.AgentTaskType;
import com.visionary.agent.AgentType;
import com.visionary.client.QwenVlApiClient;
import com.visionary.dto.AgentInvokeRequest;
import com.visionary.exception.AiProviderException;
import com.visionary.exception.BizException;
import com.visionary.util.OssUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 视觉评估 Agent Handler。
 * <p>处理 VISUAL_ASSESSMENT 任务，支持两种调用模式：</p>
 * <ul>
 *   <li><b>OSS URL 模式</b>（推荐）：前端上传图片到后端，后端通过 {@link OssUtil} 上传至 OSS，
 *       获取公网 URL 后调用 {@link QwenVlApiClient#analyzeImageWithUrl}</li>
 *   <li><b>Base64 模式</b>（兼容）：直接传入 Base64 编码的图片，调用 {@link QwenVlApiClient#analyzeImageWithBase64}</li>
 * </ul>
 *
 * <p>设计规范：Handler 层不捕获业务异常，统一抛出由 {@link com.visionary.exception.GlobalExceptionHandler} 处理。</p>
 */
@Slf4j
@Service
@AgentType(AgentTaskType.VISUAL_ASSESSMENT)
@RequiredArgsConstructor
public class VisionAssessmentAgentHandler implements AgentService {

    // 允许的图片 MIME 类型
    private static final Set<String> ALLOWED_IMAGE_TYPES = Set.of(
            "image/jpeg", "image/png", "image/gif", "image/webp"
    );

    // 允许的图片扩展名
    private static final Set<String> ALLOWED_IMAGE_EXTENSIONS = Set.of(
            ".jpg", ".jpeg", ".png", ".gif", ".webp"
    );

    // 最大文件大小：10MB
    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024;
    private static final long MIN_FILE_SIZE = 1024;
    private static final int MIN_IMAGE_EDGE = 128;

    // Base64 最大长度：约 14MB 字符串（对应约 10MB 原图）
    private static final int MAX_BASE64_LENGTH = 14 * 1024 * 1024;

    // 允许的 URL 协议
    private static final Set<String> ALLOWED_URL_SCHEMES = Set.of("http", "https");

    // URL 最大长度限制
    private static final int MAX_URL_LENGTH = 2048;

    private final QwenVlApiClient qwenVlApiClient;
    private final AgentJsonParser jsonParser;
    private final OssUtil ossUtil;

    /**
     * AgentService 标准入口：从 Request 中自动判断调用方式（不带文件）。
     * <p>支持的图片输入方式：</p>
     * <ol>
     *   <li>如果 request 包含 imageUrl，直接传入 URL</li>
     *   <li>如果 request 包含 imageBase64，走 Base64 模式</li>
     * </ol>
     *
     * @throws BizException 当缺少图片输入时抛出
     * @throws AiProviderException 当 AI 服务调用失败时抛出
     */
    @Override
    public AgentResponse<AssessmentResult> process(AgentInvokeRequest request) {
        return process(request, null);
    }

    /**
     * AgentService 重载入口：支持单独传入 MultipartFile。
     * <p>优先级：</p>
     * <ol>
     *   <li>如果 multipartFile 不为空，走 OSS 上传模式</li>
     *   <li>如果 request 包含 imageUrl，直接传入 URL</li>
     *   <li>如果 request 包含 imageBase64，走 Base64 模式</li>
     * </ol>
     *
     * @param request 调用请求
     * @param file    上传的文件（可为 null）
     * @return Agent 响应结果
     * @throws BizException 当缺少图片输入时抛出
     * @throws AiProviderException 当 AI 服务调用失败时抛出
     */
    @Override
    public AgentResponse<AssessmentResult> process(AgentInvokeRequest request, MultipartFile file) {
        long start = System.currentTimeMillis();

        // 优先尝试 OSS 上传模式（MultipartFile 参数）
        if (file != null && !file.isEmpty()) {
            log.info("使用 OSS 上传模式进行视觉评估");
            AssessmentResult result = assessWithUpload(file, request.contextPrompt());
            return AgentResponse.success(
                    AgentTaskType.VISUAL_ASSESSMENT,
                    AgentTaskType.VISUAL_ASSESSMENT,
                    qwenVlApiClient.providerName(),
                    System.currentTimeMillis() - start,
                    result
            );
        }

        // 其次尝试 URL 模式
        if (request.imageUrl() != null && !request.imageUrl().isBlank()) {
            log.info("使用 URL 模式进行视觉评估");
            try {
                AssessmentResult result = assessWithUrl(request.imageUrl(), request.contextPrompt());
                return AgentResponse.success(
                        AgentTaskType.VISUAL_ASSESSMENT,
                        AgentTaskType.VISUAL_ASSESSMENT,
                        qwenVlApiClient.providerName(),
                        System.currentTimeMillis() - start,
                        result
                );
            } catch (IOException e) {
                throw new AiProviderException(qwenVlApiClient.providerName(), "视觉评估失败: " + e.getMessage(), e);
            }
        }

        // 最后尝试 Base64 模式
        if (request.imageBase64() != null && !request.imageBase64().isBlank()) {
            log.info("使用 Base64 模式进行视觉评估");
            AssessmentResult result = assessWithBase64(request.imageBase64(), request.contextPrompt());
            return AgentResponse.success(
                    AgentTaskType.VISUAL_ASSESSMENT,
                    AgentTaskType.VISUAL_ASSESSMENT,
                    qwenVlApiClient.providerName(),
                    System.currentTimeMillis() - start,
                    result
            );
        }

        // 无任何图片输入 - 抛出业务异常，由全局处理器处理
        throw new BizException("MISSING_IMAGE_INPUT", "缺少图片输入：请提供 multipartFile、imageUrl 或 imageBase64");
    }

    /**
     * OSS 上传 + 视觉评估闭环（推荐）。
     * <p>完整流程：前端 MultipartFile → 后端上传 OSS → 获取公网 URL → 调用 VL-Max 测评</p>
     *
     * @param file    前端上传的 MultipartFile
     * @param prompt  批阅提示词
     * @return 评估结果
     * @throws BizException 当文件类型或大小不合法时抛出
     * @throws AiProviderException OSS 上传或 API 调用失败时抛出
     */
    public AssessmentResult assessWithUpload(MultipartFile file, String prompt) {
        // 步骤 1: 文件类型与大小校验
        validateUploadedFile(file);

        // 步骤 2: 上传图片到 OSS
        String imageUrl;
        try {
            imageUrl = ossUtil.upload(file);
        } catch (IOException e) {
            log.error("OSS 上传失败: {}", e.getMessage(), e);
            throw new AiProviderException("OSS", "图片上传失败: " + e.getMessage(), e);
        }
        log.info("图片已上传至 OSS: {}", imageUrl);

        // 步骤 3: 使用 URL 调用视觉评估
        try {
            return assessWithUrl(imageUrl, prompt);
        } catch (IOException e) {
            // 测评失败时，可选择保留或删除 OSS 文件（此处保留用于人工复查）
            log.warn("视觉评估失败，OSS 文件保留用于复查: {}", imageUrl);
            throw new AiProviderException(qwenVlApiClient.providerName(), "视觉评估失败: " + e.getMessage(), e);
        }
    }

    /**
     * 校验上传文件的类型和大小。
     *
     * @param file 上传的文件
     * @throws BizException 当文件类型不允许或大小超限时抛出
     */
    private void validateUploadedFile(MultipartFile file) {
        // 校验文件大小
        if (file.getSize() < MIN_FILE_SIZE) {
            throw new BizException("IMAGE_TOO_SMALL",
                    "图片内容过小，无法可靠识别。请上传清晰、完整的作业图片");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new BizException("FILE_SIZE_EXCEEDED",
                    "文件大小超过限制（最大 10MB），当前大小: " + (file.getSize() / 1024 / 1024) + "MB");
        }

        // 校验 MIME 类型
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_IMAGE_TYPES.contains(contentType.toLowerCase(Locale.ROOT))) {
            throw new BizException("INVALID_FILE_TYPE",
                    "不支持的文件类型: " + contentType + "。仅支持 JPEG、PNG、GIF、WebP 格式");
        }

        // 校验文件扩展名
        String originalFilename = file.getOriginalFilename();
        if (originalFilename != null && !originalFilename.isBlank()) {
            String extension = getFileExtension(originalFilename).toLowerCase(Locale.ROOT);
            if (!ALLOWED_IMAGE_EXTENSIONS.contains(extension)) {
                throw new BizException("INVALID_FILE_EXTENSION",
                        "不支持的文件扩展名: " + extension + "。仅支持 .jpg, .jpeg, .png, .gif, .webp");
            }
        }

        if (!"image/webp".equalsIgnoreCase(contentType)) {
            try {
                BufferedImage image = ImageIO.read(file.getInputStream());
                if (image == null || image.getWidth() < MIN_IMAGE_EDGE || image.getHeight() < MIN_IMAGE_EDGE) {
                    throw new BizException("IMAGE_DIMENSIONS_TOO_SMALL",
                            "图片分辨率过低，建议上传宽高至少 128 像素的清晰图片");
                }
            } catch (IOException e) {
                throw new BizException("INVALID_IMAGE_CONTENT", "图片文件损坏或无法读取，请重新选择图片");
            }
        }

        log.info("文件校验通过: name={}, size={}, type={}",
                originalFilename, file.getSize(), contentType);
    }

    /**
     * 获取文件扩展名（不含点）。
     */
    private String getFileExtension(String filename) {
        int lastDotIndex = filename.lastIndexOf('.');
        if (lastDotIndex > 0 && lastDotIndex < filename.length() - 1) {
            return filename.substring(lastDotIndex);
        }
        return "";
    }

    /**
     * 通过图片 URL 进行视觉评估。
     *
     * @param imageUrl 图片 URL（必须为 http/https 协议）
     * @param prompt   批阅提示词
     * @return 评估结果
     * @throws BizException URL 格式不合法时抛出
     * @throws AiProviderException API 调用失败时抛出
     */
    public AssessmentResult assessWithUrl(String imageUrl, String prompt) throws IOException {
        // SSRF 防护：校验 URL 格式和协议
        validateImageUrl(imageUrl);

        if (!qwenVlApiClient.isConfigured()) {
            log.warn("Qwen-VL 未配置，使用降级策略");
            return buildFallbackResult(prompt, imageUrl);
        }

        String defaultPrompt = (prompt == null || prompt.isBlank())
                ? "请批阅这张手写高数草稿，提取结构化文本并分析错因"
                : prompt;

        String rawResponse = qwenVlApiClient.analyzeImageWithUrl(defaultPrompt, imageUrl);
        try {
            return parseAssessment(rawResponse, imageUrl);
        } catch (IllegalArgumentException firstParseFailure) {
            log.warn("Vision assessment returned malformed JSON; retrying once with strict schema: {}",
                    firstParseFailure.getMessage());
            String retryResponse = qwenVlApiClient.analyzeImageWithUrl(strictJsonPrompt(defaultPrompt), imageUrl);
            try {
                return parseAssessment(retryResponse, imageUrl);
            } catch (IllegalArgumentException secondParseFailure) {
                throw new AiProviderException(
                        qwenVlApiClient.providerName(),
                        "视觉评估结果格式异常，请重试",
                        secondParseFailure
                );
            }
        }
    }

    /**
     * 校验图片 URL，防止 SSRF 攻击和 API 盗刷。
     *
     * @param imageUrl 待校验的 URL
     * @throws BizException 当 URL 不合法时抛出
     */
    private void validateImageUrl(String imageUrl) {
        // 校验 URL 长度
        if (imageUrl.length() > MAX_URL_LENGTH) {
            throw new BizException("URL_TOO_LONG",
                    "URL 长度超过限制（最大 " + MAX_URL_LENGTH + " 字符）");
        }

        URL url;
        try {
            url = new URL(imageUrl);
        } catch (MalformedURLException e) {
            throw new BizException("INVALID_URL_FORMAT", "URL 格式错误: " + e.getMessage());
        }

        // 校验协议必须为 http 或 https
        String protocol = url.getProtocol();
        if (!ALLOWED_URL_SCHEMES.contains(protocol)) {
            throw new BizException("INVALID_URL_PROTOCOL",
                    "不支持的 URL 协议: " + protocol + "。仅支持 http:// 和 https://");
        }

        // 校验主机名不为空
        String host = url.getHost();
        if (host == null || host.isBlank()) {
            throw new BizException("INVALID_URL_HOST", "URL 主机名不能为空");
        }

        // 禁止内网地址（基础防护）
        if (isInternalHost(host)) {
            throw new BizException("FORBIDDEN_URL",
                    "不允许访问内网地址: " + host);
        }

        log.debug("URL 校验通过: {}", imageUrl);
    }

    /**
     * 检查是否为内网地址。
     * 基础实现：检查常见的内网地址前缀。
     */
    private boolean isInternalHost(String host) {
        String lowerHost = host.toLowerCase(Locale.ROOT);

        // 本地主机
        if (lowerHost.equals("localhost") || lowerHost.equals("127.0.0.1")
                || lowerHost.startsWith("127.") || lowerHost.equals("0.0.0.0")) {
            return true;
        }

        // 私有地址段
        if (lowerHost.startsWith("10.") || lowerHost.startsWith("192.168.")) {
            return true;
        }

        // 172.16.0.0 - 172.31.255.255
        if (lowerHost.startsWith("172.")) {
            try {
                int secondOctet = Integer.parseInt(lowerHost.substring(4, lowerHost.indexOf('.', 4)));
                if (secondOctet >= 16 && secondOctet <= 31) {
                    return true;
                }
            } catch (NumberFormatException | IndexOutOfBoundsException ignored) {
                // 不是 IPv4 格式，继续检查
            }
        }

        return false;
    }

    /**
     * 通过 Base64 编码进行视觉评估（兼容模式）。
     *
     * @param imageBase64 Base64 编码的图片（最大约 10MB 原图，约 14MB Base64 字符串）
     * @param prompt      批阅提示词
     * @return 评估结果
     * @throws BizException 当 Base64 超长或格式无效时抛出
     * @throws AiProviderException API 调用失败时抛出
     */
    public AssessmentResult assessWithBase64(String imageBase64, String prompt) {
        if (!qwenVlApiClient.isConfigured()) {
            return buildFallbackResult(prompt, null);
        }

        String normalized = stripDataUrlPrefix(imageBase64.trim());

        // 内存溢出防护：限制 Base64 字符串长度
        if (normalized.length() > MAX_BASE64_LENGTH) {
            throw new BizException("BASE64_TOO_LARGE",
                    "Base64 编码图片过大（最大约 10MB 原图，约 " + (MAX_BASE64_LENGTH / 1024 / 1024)
                            + "MB Base64 字符串），当前大小: "
                            + (normalized.length() / 1024 / 1024) + "MB");
        }

        byte[] imageBytes;
        try {
            // 使用 MIME 解码器增强鲁棒性，兼容带换行符的 Base64 字符串
            imageBytes = Base64.getMimeDecoder().decode(normalized);
        } catch (IllegalArgumentException e) {
            throw new BizException("INVALID_BASE64", "无效的 Base64 编码: " + e.getMessage());
        }

        String mimeType = detectMimeType(imageBytes);

        String defaultPrompt = (prompt == null || prompt.isBlank())
                ? "请批阅这张手写高数草稿，提取结构化文本并分析错因"
                : prompt;

        try {
            String rawResponse = qwenVlApiClient.analyzeImageWithBase64(defaultPrompt, imageBytes, mimeType);
            try {
                return parseAssessment(rawResponse, null);
            } catch (IllegalArgumentException firstParseFailure) {
                log.warn("Vision assessment returned malformed JSON; retrying once with strict schema: {}",
                        firstParseFailure.getMessage());
                String retryResponse = qwenVlApiClient.analyzeImageWithBase64(
                        strictJsonPrompt(defaultPrompt), imageBytes, mimeType);
                try {
                    return parseAssessment(retryResponse, null);
                } catch (IllegalArgumentException secondParseFailure) {
                    throw new AiProviderException(
                            qwenVlApiClient.providerName(),
                            "视觉评估结果格式异常，请重试",
                            secondParseFailure
                    );
                }
            }
        } catch (IOException e) {
            throw new AiProviderException(qwenVlApiClient.providerName(), "视觉评估失败: " + e.getMessage(), e);
        }
    }

    // ==================== 解析与辅助方法 ====================

    private AssessmentResult parseAssessment(String rawResponse, String imageUrl) {
        JsonNode node = jsonParser.parseLenient(rawResponse);
        String ocrText = jsonParser.text(node, "ocrText", "").trim();
        double confidence = jsonParser.number(node, "confidence", 0.75);
        String normalizedOcr = ocrText.toLowerCase(Locale.ROOT);
        if (ocrText.isBlank()
                || confidence < 0.1
                || normalizedOcr.contains("ocr unavailable")
                || ocrText.contains("图像内容为空")
                || ocrText.contains("无法进行 OCR")) {
            throw new BizException("INVALID_ASSESSMENT_IMAGE",
                    "没有识别到有效作业内容，本次测评不会写入学习画像。请上传更清晰、完整的图片后重试");
        }
        return new AssessmentResult(
                ocrText,
                jsonParser.text(node, "errorPattern", "unspecified"),
                jsonParser.text(node, "correctiveFeedback", rawResponse),
                confidence,
                imageUrl
        );
    }

    private static String strictJsonPrompt(String originalPrompt) {
        return originalPrompt + """

                \n上一次响应无法解析。请重新识别，并且只输出一个合法 JSON 对象，不要 Markdown 代码块、注释或额外文字。
                必须包含：ocrText(string), errorPattern(string), correctiveFeedback(string), confidence(number, 0-1)。
                """;
    }

    private AssessmentResult buildFallbackResult(String contextPrompt, String imageUrl) {
        String topic = contextPrompt == null || contextPrompt.isBlank() ? "draft work" : contextPrompt.trim();
        return new AssessmentResult(
                "OCR unavailable in fallback mode.",
                "Potential notation or intermediate-step gap",
                "Review each derivation step for " + topic + " and verify boundary conditions at edges/corners.",
                0.55,
                imageUrl
        );
    }

    private String stripDataUrlPrefix(String base64) {
        int commaIndex = base64.indexOf(',');
        if (base64.startsWith("data:") && commaIndex > 0) {
            return base64.substring(commaIndex + 1);
        }
        return base64;
    }

    private String detectMimeType(byte[] imageBytes) {
        // 优先使用 URLConnection 进行 MIME 类型探测
        try {
            String mimeType = URLConnection.guessContentTypeFromStream(new ByteArrayInputStream(imageBytes));
            if (mimeType != null && !mimeType.isBlank()) {
                return mimeType;
            }
        } catch (IOException ignored) {
            // Fall through to magic-number detection.
        }

        // 备用逻辑：基于魔法数字的稳健检测
        return detectMimeTypeByMagicNumber(imageBytes);
    }

    private String detectMimeTypeByMagicNumber(byte[] imageBytes) {
        if (imageBytes.length < 4) {
            return "application/octet-stream";
        }

        int b0 = imageBytes[0] & 0xFF;
        int b1 = imageBytes[1] & 0xFF;
        int b2 = imageBytes[2] & 0xFF;
        int b3 = imageBytes[3] & 0xFF;

        // PNG: 89 50 4E 47
        if (b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && b3 == 0x47) {
            return "image/png";
        }

        // JPEG: FF D8 FF
        if (b0 == 0xFF && b1 == 0xD8 && b2 == 0xFF) {
            return "image/jpeg";
        }

        // GIF: GIF87a or GIF89a
        if (b0 == 0x47 && b1 == 0x49 && b2 == 0x46 && b3 == 0x38) {
            return "image/gif";
        }

        // WebP: RIFF....WEBP
        if (imageBytes.length >= 12
                && b0 == 0x52 && b1 == 0x49 && b2 == 0x46 && b3 == 0x46) {
            int b8 = imageBytes[8] & 0xFF;
            int b9 = imageBytes[9] & 0xFF;
            int b10 = imageBytes[10] & 0xFF;
            int b11 = imageBytes[11] & 0xFF;
            if (b8 == 0x57 && b9 == 0x45 && b10 == 0x42 && b11 == 0x50) {
                return "image/webp";
            }
        }

        // BMP: BM
        if (b0 == 0x42 && b1 == 0x4D) {
            return "image/bmp";
        }

        // TIFF: II (little endian) or MM (big endian)
        if ((b0 == 0x49 && b1 == 0x49 && b2 == 0x2A && b3 == 0x00)
                || (b0 == 0x4D && b1 == 0x4D && b2 == 0x00 && b3 == 0x2A)) {
            return "image/tiff";
        }

        // 无法识别时返回通用二进制类型，而非默认 PNG
        return "application/octet-stream";
    }

    // ==================== 结果记录 ====================

    public record AssessmentResult(
            String ocrText,
            String errorPattern,
            String correctiveFeedback,
            double confidence,
            String imageUrl  // OSS 图片 URL，用于复查
    ) {
    }
}
