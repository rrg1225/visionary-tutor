package com.visionary.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.visionary.config.AiApiConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.File;
import java.nio.file.Files;

/**
 * 通义千问 VL-Max 视觉模型 API 客户端。
 * <p>支持两种图片输入方式：</p>
 * <ul>
 *   <li>{@link #analyzeImageWithBase64} - 直接上传 Base64 编码的图片数据</li>
 *   <li>{@link #analyzeImageWithUrl} - 传入公网可访问的图片 URL（推荐，减少带宽）</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QwenVlApiClient {

    private static final String PROVIDER = "qwen-vl-max";

    private final AiApiConfig config;
    private final HttpAiClientSupport httpSupport;
    private final ObjectMapper objectMapper;

    /**
     * 通过 Base64 编码分析图片（兼容模式，适合小图）。
     *
 * @param contextPrompt 上下文提示词
     * @param imageBytes    图片字节数组
     * @param mimeType      MIME 类型（如 image/png）
     * @return AI 分析结果 JSON 字符串
     * @throws IOException API 调用失败
     */
    public String analyzeImageWithBase64(String contextPrompt, byte[] imageBytes, String mimeType) throws IOException {
        String base64 = java.util.Base64.getEncoder().encodeToString(imageBytes);
        String dataUrl = "data:" + mimeType + ";base64," + base64;
        return analyzeImageInternal(contextPrompt, dataUrl, true);
    }

    /**
     * 通过图片 URL 分析图片（推荐模式，适合大图和减少带宽）。
     *
     * @param contextPrompt 上下文提示词，如 "请批阅这张手写高数草稿，提取结构化文本并分析错因"
     * @param imageUrl      公网可访问的图片 URL（如 OSS 返回的 URL）
     * @return AI 分析结果 JSON 字符串
     * @throws IOException API 调用失败
     */
    public String analyzeImageWithUrl(String contextPrompt, String imageUrl) throws IOException {
        return analyzeImageInternal(contextPrompt, imageUrl, false);
    }

    /**
     * 核心分析逻辑：组装请求并调用通义千问 VL-Max API。
     *
     * @param prompt       提示词
     * @param imageSource  图片数据源（URL 或 Base64 Data URL）
     * @param isBase64     是否为 Base64 Data URL
     * @return AI 响应内容
     * @throws IOException 调用失败
     */
    private String analyzeImageInternal(String prompt, String imageSource, boolean isBase64) throws IOException {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", config.getQwenVlModel());
        body.put("temperature", 0.2);

        ArrayNode messages = body.putArray("messages");
        ObjectNode user = messages.addObject();
        user.put("role", "user");

        ArrayNode content = user.putArray("content");
        ObjectNode textPart = content.addObject();
        textPart.put("type", "text");
        textPart.put("text", buildVisionPrompt(prompt));

        ObjectNode imagePart = content.addObject();
        imagePart.put("type", "image_url");
        ObjectNode imageUrl = imagePart.putObject("image_url");
        imageUrl.put("url", imageSource);
        if (isBase64) {
            imageUrl.put("detail", "auto");
        }

        String url = config.getQwenBaseUrl() + "/chat/completions";
        String responseJson = httpSupport.postJsonWithRetry(url, config.getQwenVlMaxKey(), body.toString());
        String result = httpSupport.extractAssistantContent(responseJson);

        log.debug("Qwen-VL 分析完成，输入长度: {}, 输出长度: {}",
                imageSource.length(), result.length());

        return result;
    }

    /**
     * 描述本地图像文件，用于 Caption-based Multi-modal RAG 摄取。
     * 正确编码本地 File 为 Base64，构造 Qwen-VL 请求，使用给定 prompt 直接描述教学图像。
     */
    public String describeLocalImage(File imageFile, String prompt) throws IOException {
        if (imageFile == null || !imageFile.exists() || !imageFile.isFile()) {
            throw new IOException("Image file not found or invalid: " + imageFile);
        }
        byte[] imageBytes = Files.readAllBytes(imageFile.toPath());
        String mimeType = guessMimeType(imageFile.getName());
        String base64 = java.util.Base64.getEncoder().encodeToString(imageBytes);
        String dataUrl = "data:" + mimeType + ";base64," + base64;
        return analyzeImageInternalDirect(prompt != null ? prompt : "", dataUrl);
    }

    private String guessMimeType(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        return "image/png";
    }

    /**
     * 直接使用用户 prompt 的内部分析（不包装为数学批改提示），用于图像字幕生成。
     */
    private String analyzeImageInternalDirect(String prompt, String imageSource) throws IOException {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", config.getQwenVlModel());
        body.put("temperature", 0.2);

        ArrayNode messages = body.putArray("messages");
        ObjectNode user = messages.addObject();
        user.put("role", "user");

        ArrayNode content = user.putArray("content");
        ObjectNode textPart = content.addObject();
        textPart.put("type", "text");
        textPart.put("text", prompt);

        ObjectNode imagePart = content.addObject();
        imagePart.put("type", "image_url");
        ObjectNode imageUrl = imagePart.putObject("image_url");
        imageUrl.put("url", imageSource);
        imageUrl.put("detail", "auto");

        String url = config.getQwenBaseUrl() + "/chat/completions";
        String responseJson = httpSupport.postJsonWithRetry(url, config.getQwenVlMaxKey(), body.toString());
        String result = httpSupport.extractAssistantContent(responseJson);

        log.debug("Qwen-VL 图像描述完成，输出长度: {}", result.length());
        return result;
    }

    public boolean isConfigured() {
        return config.isQwenConfigured();
    }

    public String providerName() {
        return PROVIDER;
    }

    /**
     * 构建视觉测评提示词。
     * 用于高数草稿批阅场景。
     */
    private String buildVisionPrompt(String contextPrompt) {
        String prompt = contextPrompt == null ? "" : contextPrompt.trim();
        if (prompt.isBlank()) {
            prompt = "请批阅这张手写高数草稿，提取结构化文本并分析错因";
        }

        return """
                你是一位资深的高等数学教授，专门负责批阅学生的手写草稿和作业。

                请仔细分析上传的图片，完成以下任务：
                1. **OCR 文本提取**：准确识别手写内容，包括数学公式、推导步骤、计算过程
                2. **错误识别**：指出计算错误、概念误解、逻辑漏洞
                3. **结构化分析**：分析解题思路的完整性和合理性
                4. **改进建议**：给出具体的修正方法和学习建议

                返回严格 JSON 格式，包含以下字段：
                - ocrText: 提取的完整文本内容
                - errorPattern: 主要错误类型（如 "计算错误"/"概念错误"/"逻辑漏洞"/"无错误"）
                - correctiveFeedback: 详细的批改反馈和改进建议
                - confidence: 置信度 (0.0-1.0)

                用户指令：%s
                """.formatted(prompt);
    }
}
