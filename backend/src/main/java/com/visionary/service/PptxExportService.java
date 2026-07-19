package com.visionary.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.visionary.config.PptxProperties;
import com.visionary.dto.PptxExportResult;
import com.visionary.dto.PptxEditExportRequest;
import com.visionary.entity.GeneratedArtifact;
import com.visionary.exception.ResourceNotFoundException;
import com.visionary.repository.GeneratedArtifactRepository;
import com.visionary.repository.LearningSessionRepository;
import com.visionary.util.OssUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.apache.poi.sl.usermodel.TextParagraph;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xslf.usermodel.XSLFTextParagraph;
import org.apache.poi.xslf.usermodel.XSLFTextRun;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.geom.Rectangle2D;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * PPTX Export Service.
 * Invokes ai_engine/pptx_generator.py (standard) or pptx_premium_exporter.py (premium).
 * Premium 路径任何失败均静默 fallback，绝不向前端抛 500。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PptxExportService {

    private static final Pattern MD_BULLET = Pattern.compile("^[-*+]\\s+");
    private static final Pattern MD_ORDERED = Pattern.compile("^\\d+\\.\\s+");

    private final GeneratedArtifactRepository artifactRepository;
    private final LearningSessionRepository learningSessionRepository;
    private final OssUtil ossUtil;
    private final ObjectMapper objectMapper;
    private final PptxProperties pptxProperties;

    /**
     * Python 工具链可用性（懒探测 + 进程级缓存）。
     * 云端宝塔环境没有 Python，此时所有导出直接走 Java POI 主路径，
     * 避免每次导出都白等 ProcessBuilder 失败/超时。
     */
    private volatile Boolean pythonToolchainAvailable;

    boolean isPythonToolchainAvailable() {
        Boolean cached = pythonToolchainAvailable;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (pythonToolchainAvailable != null) {
                return pythonToolchainAvailable;
            }
            boolean available = probePythonToolchain();
            pythonToolchainAvailable = available;
            return available;
        }
    }

    private boolean probePythonToolchain() {
        if (!Files.exists(Path.of(pptxProperties.getScriptPath()))) {
            log.warn("[pptx] 脚本 {} 不存在，PPTX 导出固定走 Java POI 路径", pptxProperties.getScriptPath());
            return false;
        }
        try {
            Process process = new ProcessBuilder(pptxProperties.getPythonPath(), "--version")
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("[pptx] python --version 探测超时，PPTX 导出固定走 Java POI 路径");
                return false;
            }
            if (process.exitValue() != 0) {
                log.warn("[pptx] python --version 退出码 {}，PPTX 导出固定走 Java POI 路径", process.exitValue());
                return false;
            }
            log.info("[pptx] Python 工具链可用，PPTX 优先走 Python 精排路径");
            return true;
        } catch (Exception e) {
            log.warn("[pptx] Python 不可用（{}），PPTX 导出固定走 Java POI 路径", e.getMessage());
            return false;
        }
    }

    /**
     * Generate PPTX for a GeneratedArtifact and return the file bytes.
     */
    public byte[] exportPptx(Long artifactId) {
        GeneratedArtifact artifact = artifactRepository.findById(artifactId)
                .orElseThrow(() -> new ResourceNotFoundException("Artifact not found: " + artifactId));

        String title = artifact.getTitle() != null ? artifact.getTitle() : "学习资源";
        String content = artifact.getContentMarkdown() != null ? artifact.getContentMarkdown() : "";
        String type = artifact.getArtifactType() != null ? artifact.getArtifactType().name() : "HANDOUT";

        if (!isPythonToolchainAvailable()) {
            return generateJavaFallbackSafely(title, splitMarkdownLines(content), "智眸学伴 · 资源导出");
        }

        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("visionary-pptx-");
            Path output = tempDir.resolve("resource-" + artifactId + ".pptx");

            ProcessBuilder pb = new ProcessBuilder(
                    pptxProperties.getPythonPath(),
                    pptxProperties.getScriptPath(),
                    "--title", title,
                    "--content", content.length() > 3000 ? content.substring(0, 3000) : content,
                    "--type", type,
                    "--output", output.toAbsolutePath().toString()
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.info("[pptx] {}", line);
                }
            }

            boolean finished = process.waitFor(60, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new RuntimeException("PPTX generation timeout");
            }
            if (process.exitValue() != 0) {
                throw new RuntimeException("PPTX generator failed with code " + process.exitValue());
            }

            return requireValidPptx(Files.readAllBytes(output));

        } catch (Exception e) {
            log.warn("Python PPTX export failed for artifact {}, using Java fallback: {}", artifactId, e.getMessage());
            return generateJavaFallbackSafely(title, splitMarkdownLines(content), "智眸学伴 · 资源导出");
        } finally {
            if (tempDir != null) {
                deleteDirectoryRecursively(tempDir);
            }
        }
    }

    /**
     * Export full session resources as one PPTX (standard path, backward compatible).
     */
    public byte[] exportSessionPptx(Long learningSessionId) {
        return exportSessionPptx(learningSessionId, "standard").bytes();
    }

    /**
     * Premium 增强版导出入口（quality=premium|standard）。
     * 任何 premium 异常静默 fallback，返回 PptxExportResult 携带真实 exportMode。
     */
    public PptxExportResult exportSessionPptx(Long sessionId, String quality) {
        Map<String, Object> payload = buildSessionExportPayload(sessionId);

        if (!isPythonToolchainAvailable()) {
            return PptxExportResult.javaFallback(generateJavaFallbackFromPayload(payload));
        }

        boolean requestPremium = "premium".equalsIgnoreCase(quality) && pptxProperties.getPremium().isEnabled();

        if (!requestPremium) {
            try {
                byte[] bytes = runStandardExportFromPayload(payload, sessionId);
                return PptxExportResult.standard(bytes);
            } catch (Exception e) {
                log.warn("Standard session PPTX export failed, using Java fallback: {}", e.getMessage());
                return PptxExportResult.javaFallback(generateJavaFallbackFromPayload(payload));
            }
        }

        try {
            byte[] bytes = runPremiumExport(payload, sessionId);
            return PptxExportResult.premium(bytes);
        } catch (Exception e) {
            log.warn("Premium export failed for session {}, fallback to standard: {}", sessionId, e.getMessage());
            try {
                byte[] bytes = runStandardExportFromPayload(payload, sessionId);
                return PptxExportResult.standardFallback(bytes);
            } catch (Exception fallbackErr) {
                log.warn("Standard fallback also failed, using Java fallback: {}", fallbackErr.getMessage());
                return PptxExportResult.javaFallback(generateJavaFallbackFromPayload(payload));
            }
        }
    }

    /**
     * Export user-reviewed slide titles and bodies. This path is deterministic so
     * the downloaded deck always reflects the thumbnail editor exactly.
     */
    public byte[] exportEditedPptx(PptxEditExportRequest request) {
        try (XMLSlideShow deck = new XMLSlideShow(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            deck.setPageSize(new Dimension(960, 540));
            addTitleSlide(
                    deck,
                    request.deckTitle().trim(),
                    request.subtitle() == null || request.subtitle().isBlank()
                            ? "智眸学伴 · 编辑后导出"
                            : request.subtitle().trim()
            );
            for (PptxEditExportRequest.SlideEdit slide : request.slides()) {
                addEditedContentSlide(deck, slide.title().trim(), slide.body().trim());
            }
            deck.write(output);
            return requireValidPptx(output.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException("Edited PPTX export failed", e);
        }
    }

    /**
     * 从 DB artifacts 构建导出 payload，缺字段给安全默认值，永不抛 KeyError 类异常给前端。
     */
    Map<String, Object> buildSessionExportPayload(Long sessionId) {
        List<GeneratedArtifact> artifacts = artifactRepository
                .findByLearningSessionIdOrderByGmtCreatedDesc(sessionId);

        if (artifacts.isEmpty()) {
            throw new ResourceNotFoundException("No artifacts for session: " + sessionId);
        }

        Map<String, String> byType = artifacts.stream()
                .collect(Collectors.toMap(
                        a -> a.getArtifactType() != null ? a.getArtifactType().name() : "UNKNOWN",
                        a -> a.getContentMarkdown() != null ? a.getContentMarkdown() : "",
                        (a, b) -> a
                ));

        String topic = artifacts.get(0).getTitle();
        if (topic == null || topic.isBlank()) {
            topic = "课程资源包";
        }

        String profile = "学生画像已融合（弱点、情绪、knowledgeState），资源由多 Agent 个性化生成。";
        String handoutMd = byType.getOrDefault("HANDOUT", "");
        String quizMd = byType.getOrDefault("QUIZ", "");
        String mindmapMd = byType.getOrDefault("MINDMAP", "");
        String animationNotes = stripHtmlForExport(byType.getOrDefault("VISUALIZATION", ""));

        Map<String, Object> jsonData = new LinkedHashMap<>();
        jsonData.put("sessionId", sessionId);
        jsonData.put("topic", topic);
        jsonData.put("subtitle", "智眸学伴 · 多智能体个性化教学资源包");
        jsonData.put("studentProfile", profile);
        // premium normalizer 期望 list；standard generator 也兼容 list（会 str() 处理）
        jsonData.put("handout", splitMarkdownLines(handoutMd));
        jsonData.put("quiz", extractQuizStems(quizMd));
        jsonData.put("mindmap", mindmapMd != null ? mindmapMd : "");
        // Keep the legacy payload key for the existing Python template, but feed it local-animation notes.
        jsonData.put("videoScript", splitMarkdownLines(animationNotes));
        jsonData.put("citations", List.of(
                "visionary_global_knowledge RAG 集合",
                "CitationValidator + FactualityVerifier 校验"
        ));
        jsonData.put("footerNote", "完整讲义见智眸学伴资源卡片 · DocAgent");
        return jsonData;
    }

    private byte[] runStandardExportFromPayload(Map<String, Object> payload, Long sessionId) throws Exception {
        Path tempDir = Files.createTempDirectory("visionary-pptx-" + UUID.randomUUID());
        Path jsonFile = tempDir.resolve("data.json");
        Path output = tempDir.resolve("session-" + sessionId + ".pptx");

        try {
            objectMapper.writeValue(jsonFile.toFile(), payload);

            ProcessBuilder pb = new ProcessBuilder(
                    pptxProperties.getPythonPath(),
                    pptxProperties.getScriptPath(),
                    "--json", jsonFile.toAbsolutePath().toString(),
                    "--output", output.toAbsolutePath().toString()
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();
            drainProcessOutput(process, "[pptx-session]");

            boolean finished = process.waitFor(90, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new RuntimeException("PPTX session generation timeout");
            }
            if (process.exitValue() != 0) {
                throw new RuntimeException("PPTX generator failed: " + process.exitValue());
            }

            byte[] bytes = requireValidPptx(Files.readAllBytes(output));
            maybeUploadToOss(sessionId, bytes);
            return bytes;
        } finally {
            deleteDirectoryRecursively(tempDir);
        }
    }

    private byte[] runPremiumExport(Map<String, Object> payload, Long sessionId) throws Exception {
        Path tempDir = Files.createTempDirectory("visionary-pptx-" + UUID.randomUUID());
        Path dataJson = tempDir.resolve("data.json");
        Path outputPptx = tempDir.resolve("premium-" + sessionId + ".pptx");

        try {
            objectMapper.writeValue(dataJson.toFile(), payload);

            ProcessBuilder pb = new ProcessBuilder(
                    pptxProperties.getPythonPath(),
                    pptxProperties.getPremiumScriptPath(),
                    "--json", dataJson.toAbsolutePath().toString(),
                    "--template", pptxProperties.getTemplate().getDir(),
                    "--output", outputPptx.toAbsolutePath().toString(),
                    "--base-name", pptxProperties.getTemplate().getBaseName()
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();
            drainProcessOutput(process, "[pptx-premium]");

            int timeoutSec = pptxProperties.getPremium().getTimeoutSeconds();
            boolean finished = process.waitFor(timeoutSec, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new RuntimeException("Premium PPTX timeout after " + timeoutSec + "s");
            }
            if (process.exitValue() != 0) {
                throw new RuntimeException("Premium exporter exit code: " + process.exitValue());
            }

            return requireValidPptx(Files.readAllBytes(outputPptx));
        } finally {
            deleteDirectoryRecursively(tempDir);
        }
    }

    private void drainProcessOutput(Process process, String prefix) throws Exception {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.info("{} {}", prefix, line);
            }
        }
    }

    private void maybeUploadToOss(Long sessionId, byte[] bytes) {
        if (!ossUtil.isAvailable()) {
            return;
        }
        try {
            String key = "pptx/session-" + sessionId + "-" + System.currentTimeMillis() + ".pptx";
            log.info("[pptx] OSS upload would store key={} ({} bytes)", key, bytes.length);
        } catch (Exception e) {
            log.warn("OSS upload check failed for session PPTX: {}", e.getMessage());
        }
    }

    byte[] generateJavaFallbackFromPayload(Map<String, Object> payload) {
        String title = String.valueOf(payload.getOrDefault("topic", "课程资源包"));
        List<String> lines = new ArrayList<>();
        for (String key : List.of("studentProfile", "handout", "mindmap", "quiz", "videoScript", "citations")) {
            Object value = payload.get(key);
            if (value instanceof Collection<?> collection) {
                collection.stream().map(String::valueOf).forEach(lines::add);
            } else if (value != null && !String.valueOf(value).isBlank()) {
                lines.addAll(splitMarkdownLines(String.valueOf(value)));
            }
        }
        return generateJavaFallbackSafely(title, lines, "智眸学伴 · 多资源学习包");
    }

    /**
     * Java POI 导出的最终防线：正常路径失败时仍返回一份最小可用 PPTX，
     * 绝不向 Controller 抛异常（线上曾因此出现 500）。
     */
    byte[] generateJavaFallbackSafely(String title, List<String> sourceLines, String subtitle) {
        try {
            return generateJavaFallback(title, sourceLines, subtitle);
        } catch (Exception e) {
            log.error("[pptx] Java POI 导出失败，降级为最小占位 PPTX: {}", e.getMessage(), e);
            try (XMLSlideShow deck = new XMLSlideShow(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                deck.setPageSize(new Dimension(960, 540));
                addTitleSlide(deck, title == null || title.isBlank() ? "学习资源" : title,
                        "导出服务繁忙，本文件仅含标题页，请稍后重试完整导出");
                deck.write(output);
                return output.toByteArray();
            } catch (Exception fatal) {
                throw new IllegalStateException("PPTX 导出服务暂时不可用，请稍后重试", fatal);
            }
        }
    }

    byte[] generateJavaFallback(String title, List<String> sourceLines, String subtitle) {
        try (XMLSlideShow deck = new XMLSlideShow(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            deck.setPageSize(new Dimension(960, 540));
            addTitleSlide(deck, title, subtitle);
            List<String> lines = sourceLines == null ? List.of() : sourceLines.stream()
                    .map(line -> line == null ? "" : line.replaceAll("\\s+", " ").trim())
                    .filter(line -> !line.isBlank())
                    .limit(70)
                    .toList();
            if (lines.isEmpty()) {
                lines = List.of("本资源暂无可导出的正文内容。请返回资源页重新生成后再试。");
            }
            for (int offset = 0, page = 1; offset < lines.size(); offset += 7, page++) {
                addContentSlide(deck, "学习内容 " + page, lines.subList(offset, Math.min(offset + 7, lines.size())));
            }
            deck.write(output);
            return requireValidPptx(output.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException("Java PPTX fallback failed", e);
        }
    }

    private void addTitleSlide(XMLSlideShow deck, String title, String subtitle) {
        XSLFSlide slide = deck.createSlide();
        slide.getBackground().setFillColor(new Color(15, 23, 42));
        addTextBox(slide, title, new Rectangle2D.Double(72, 150, 816, 110), 30, true, Color.WHITE);
        addTextBox(slide, subtitle, new Rectangle2D.Double(72, 285, 816, 54), 16, false, new Color(148, 210, 189));
    }

    private void addContentSlide(XMLSlideShow deck, String heading, List<String> lines) {
        XSLFSlide slide = deck.createSlide();
        slide.getBackground().setFillColor(new Color(248, 250, 252));
        addTextBox(slide, heading, new Rectangle2D.Double(56, 34, 848, 52), 24, true, new Color(15, 23, 42));
        XSLFTextBox body = slide.createTextBox();
        body.setAnchor(new Rectangle2D.Double(70, 104, 820, 380));
        body.setWordWrap(true);
        body.clearText();
        for (String line : lines) {
            XSLFTextParagraph paragraph = body.addNewTextParagraph();
            paragraph.setBullet(true);
            paragraph.setLeftMargin(26D);
            paragraph.setIndent(-14D);
            paragraph.setSpaceAfter(10D);
            XSLFTextRun run = paragraph.addNewTextRun();
            run.setText(line.length() > 180 ? line.substring(0, 177) + "…" : line);
            run.setFontFamily("Microsoft YaHei");
            run.setFontSize(16D);
            run.setFontColor(new Color(51, 65, 85));
        }
    }

    private void addEditedContentSlide(XMLSlideShow deck, String heading, String bodyText) {
        XSLFSlide slide = deck.createSlide();
        slide.getBackground().setFillColor(new Color(248, 250, 252));
        addTextBox(slide, heading, new Rectangle2D.Double(56, 34, 848, 58), 24, true, new Color(15, 23, 42));

        XSLFTextBox body = slide.createTextBox();
        body.setAnchor(new Rectangle2D.Double(68, 108, 824, 370));
        body.setWordWrap(true);
        body.clearText();
        List<String> paragraphs = Arrays.stream(bodyText.split("\\R"))
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .limit(16)
                .toList();
        if (paragraphs.isEmpty()) {
            paragraphs = List.of(bodyText);
        }
        int totalLength = paragraphs.stream().mapToInt(String::length).sum();
        double fontSize = totalLength > 900 ? 13D : totalLength > 560 ? 15D : 17D;
        for (String rawLine : paragraphs) {
            boolean bullet = MD_BULLET.matcher(rawLine).find() || MD_ORDERED.matcher(rawLine).find();
            String line = MD_BULLET.matcher(rawLine).replaceFirst("");
            line = MD_ORDERED.matcher(line).replaceFirst("").trim();
            XSLFTextParagraph paragraph = body.addNewTextParagraph();
            paragraph.setSpaceAfter(8D);
            if (bullet) {
                paragraph.setBullet(true);
                paragraph.setLeftMargin(24D);
                paragraph.setIndent(-12D);
            }
            XSLFTextRun run = paragraph.addNewTextRun();
            run.setText(line.length() > 420 ? line.substring(0, 417) + "…" : line);
            run.setFontFamily("Microsoft YaHei");
            run.setFontSize(fontSize);
            run.setFontColor(new Color(51, 65, 85));
        }
        addTextBox(slide, "Visionary Tutor · 可编辑幻灯片", new Rectangle2D.Double(68, 495, 824, 22), 9, false,
                new Color(100, 116, 139));
    }

    private void addTextBox(
            XSLFSlide slide,
            String text,
            Rectangle2D anchor,
            double fontSize,
            boolean bold,
            Color color
    ) {
        XSLFTextBox box = slide.createTextBox();
        box.setAnchor(anchor);
        box.clearText();
        XSLFTextParagraph paragraph = box.addNewTextParagraph();
        paragraph.setTextAlign(TextParagraph.TextAlign.LEFT);
        XSLFTextRun run = paragraph.addNewTextRun();
        run.setText(text == null || text.isBlank() ? "学习资源" : text);
        run.setFontFamily("Microsoft YaHei");
        run.setFontSize(fontSize);
        run.setBold(bold);
        run.setFontColor(color);
    }

    private byte[] requireValidPptx(byte[] bytes) {
        if (bytes == null || bytes.length < 1000 || bytes[0] != 'P' || bytes[1] != 'K') {
            throw new IllegalArgumentException("PPTX generator returned an invalid or empty file");
        }
        return bytes;
    }

    /**
     * Markdown 按行拆分，去除标题行与列表符号，供 normalizer 截断。
     */
    List<String> splitMarkdownLines(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return List.of();
        }
        return Arrays.stream(markdown.split("\n"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .filter(s -> !s.startsWith("#"))
                .map(s -> MD_BULLET.matcher(s).replaceFirst(""))
                .map(s -> MD_ORDERED.matcher(s).replaceFirst(""))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    /**
     * 从题库 Markdown 提取题干行（丢弃解析段落）。
     */
    List<String> extractQuizStems(String quizMd) {
        if (quizMd == null || quizMd.isBlank()) {
            return List.of();
        }
        List<String> stems = new ArrayList<>();
        for (String line : quizMd.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            // 跳过明显是解析/答案的行
            String lower = trimmed.toLowerCase();
            if (lower.startsWith("解析") || lower.startsWith("答案") || lower.startsWith("answer")
                    || lower.startsWith("explanation")) {
                continue;
            }
            stems.add(MD_BULLET.matcher(trimmed).replaceFirst("").trim());
        }
        return stems;
    }

    private static String stripHtmlForExport(String html) {
        if (html == null || html.isBlank()) return "";
        return html
                .replaceAll("(?is)<script[^>]*>.*?</script>", " ")
                .replaceAll("(?is)<style[^>]*>.*?</style>", " ")
                .replaceAll("(?s)<[^>]+>", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private void deleteDirectoryRecursively(Path dir) {
        try {
            if (Files.exists(dir)) {
                Files.walk(dir)
                        .sorted(Comparator.reverseOrder())
                        .forEach(p -> {
                            try {
                                Files.deleteIfExists(p);
                            } catch (Exception ignored) {
                                // 清理失败不阻断主流程
                            }
                        });
            }
        } catch (Exception ignored) {
            // 清理失败不阻断主流程
        }
    }
}
