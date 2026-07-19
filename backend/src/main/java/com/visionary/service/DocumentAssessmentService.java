package com.visionary.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.visionary.agent.AgentJsonParser;
import com.visionary.agent.AgentResponse;
import com.visionary.agent.AgentTaskType;
import com.visionary.agent.handler.VisionAssessmentAgentHandler;
import com.visionary.client.DeepSeekApiClient;
import com.visionary.rag.RagRetrievalResult;
import com.visionary.rag.RagRetrievalService;
import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
@RequiredArgsConstructor
public class DocumentAssessmentService {

    private static final long MAX_UPLOAD_BYTES = 20L * 1024 * 1024;
    private static final long MAX_EXTRACTED_BYTES = 24L * 1024 * 1024;
    private static final int MAX_TEXT_CHARS = 24_000;
    private static final int MAX_ZIP_ENTRIES = 30;
    private static final Set<String> TEXT_CODE = Set.of("txt", "md", "py", "java", "js", "ts", "jsx", "tsx", "c", "h", "cpp", "hpp", "go", "rs", "html", "css", "json", "ipynb", "sql", "yaml", "yml");
    private static final Set<String> ALLOWED = java.util.stream.Stream.concat(
            Set.of("pdf", "docx", "pptx", "zip").stream(), TEXT_CODE.stream()).collect(java.util.stream.Collectors.toUnmodifiableSet());

    private final DeepSeekApiClient deepSeekApiClient;
    private final RagRetrievalService ragRetrievalService;
    private final AgentJsonParser jsonParser;

    public AgentResponse<VisionAssessmentAgentHandler.AssessmentResult> assess(MultipartFile file, String prompt)
            throws IOException {
        long start = System.currentTimeMillis();
        validate(file);
        String extracted = extract(file.getBytes(), extension(file.getOriginalFilename()), 0).trim();
        if (extracted.length() < 20) {
            throw new IllegalArgumentException("没有从文档中提取到足够的可评估文字，请检查文件是否为扫描件或空文档");
        }
        extracted = extracted.substring(0, Math.min(MAX_TEXT_CHARS, extracted.length()));
        String question = prompt == null || prompt.isBlank() ? "请评估这份作业或学习文档" : prompt.trim();
        RagRetrievalResult rag = ragRetrievalService.retrieveForTask(
                AgentTaskType.KNOWLEDGE_DIAGNOSIS,
                question + " " + extracted.substring(0, Math.min(1800, extracted.length()))
        );

        VisionAssessmentAgentHandler.AssessmentResult result;
        AgentResponse.ResponseStatus status = AgentResponse.ResponseStatus.SUCCESS;
        if (!deepSeekApiClient.isConfigured()) {
            result = new VisionAssessmentAgentHandler.AssessmentResult(
                    extracted,
                    "文档已解析，AI 评估模型暂未配置",
                    "已成功读取文档，但暂时不能可靠判断对错。请配置 DeepSeek 后重新提交；本次内容不会被标记为已掌握。",
                    0.0,
                    null
            );
            status = AgentResponse.ResponseStatus.FALLBACK;
        } else {
            result = generateAssessment(question, extracted, rag);
        }

        if (status == AgentResponse.ResponseStatus.FALLBACK) {
            return AgentResponse.fallback(
                    AgentTaskType.VISUAL_ASSESSMENT,
                    AgentTaskType.VISUAL_ASSESSMENT,
                    deepSeekApiClient.providerName(),
                    System.currentTimeMillis() - start,
                    "文档解析成功，评估模型未配置",
                    result
            );
        }
        return AgentResponse.success(
                AgentTaskType.VISUAL_ASSESSMENT,
                AgentTaskType.VISUAL_ASSESSMENT,
                deepSeekApiClient.providerName(),
                System.currentTimeMillis() - start,
                result
        );
    }

    private VisionAssessmentAgentHandler.AssessmentResult generateAssessment(
            String question,
            String extracted,
            RagRetrievalResult rag
    ) throws IOException {
        String response = deepSeekApiClient.chat(
                "你是作业与学习文档评估智能体。只依据上传正文和可信检索证据判断，不把缺失内容猜成错误。仅输出 JSON。",
                """
                评估要求：%s

                上传文档正文：
                %s

                可信检索证据：
                %s

                只输出 JSON：
                {"ocrText":"提取后的关键作答/正文摘要","errorPattern":"具体错误模式或未发现明确错误","correctiveFeedback":"直接结论、错因、修正步骤、验证方法","confidence":0.0}
                confidence 必须为 0—1；证据不足时降低置信度并明确说明。
                """.formatted(question, extracted, rag.hasGroundedEvidence() ? rag.groundedContextBlock() : "未检索到足够证据"),
                false
        );
        JsonNode node = jsonParser.parseLenient(response);
        return new VisionAssessmentAgentHandler.AssessmentResult(
                jsonParser.text(node, "ocrText", extracted.substring(0, Math.min(3000, extracted.length()))),
                jsonParser.text(node, "errorPattern", "未发现明确错误模式"),
                jsonParser.text(node, "correctiveFeedback", "请结合原文逐项核对。"),
                Math.max(0.0, Math.min(1.0, jsonParser.number(node, "confidence", 0.5))),
                null
        );
    }

    private void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("请选择要评估的文件");
        if (file.getSize() > MAX_UPLOAD_BYTES) throw new IllegalArgumentException("文档或 ZIP 不能超过 20MB");
        String ext = extension(file.getOriginalFilename());
        if (!ALLOWED.contains(ext)) {
            throw new IllegalArgumentException("不支持该文件类型；请选择 PDF、Word、PPT、文本、常见代码文件或 ZIP");
        }
    }

    private String extract(byte[] bytes, String ext, int depth) throws IOException {
        if (bytes.length > MAX_EXTRACTED_BYTES) throw new IllegalArgumentException("解压后的单个文件过大");
        return switch (ext) {
            case "txt", "md", "py", "java", "js", "ts", "jsx", "tsx", "c", "h", "cpp", "hpp", "go", "rs", "html", "css", "json", "ipynb", "sql", "yaml", "yml" -> new String(bytes, StandardCharsets.UTF_8);
            case "pdf" -> extractPdf(bytes);
            case "docx" -> extractDocx(bytes);
            case "pptx" -> extractPptx(bytes);
            case "zip" -> depth == 0 ? extractZip(bytes) : "";
            default -> "";
        };
    }

    private String extractPdf(byte[] bytes) throws IOException {
        try (PDDocument document = PDDocument.load(bytes)) {
            if (document.isEncrypted()) throw new IllegalArgumentException("暂不支持加密 PDF");
            return new PDFTextStripper().getText(document);
        }
    }

    private String extractDocx(byte[] bytes) throws IOException {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            StringBuilder text = new StringBuilder();
            document.getParagraphs().forEach(p -> text.append(p.getText()).append('\n'));
            document.getTables().forEach(table -> table.getRows().forEach(row -> row.getTableCells()
                    .forEach(cell -> text.append(cell.getText()).append('\t'))));
            return text.toString();
        }
    }

    private String extractPptx(byte[] bytes) throws IOException {
        try (XMLSlideShow deck = new XMLSlideShow(new ByteArrayInputStream(bytes))) {
            StringBuilder text = new StringBuilder();
            for (var slide : deck.getSlides()) {
                for (XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof XSLFTextShape textShape) text.append(textShape.getText()).append('\n');
                }
            }
            return text.toString();
        }
    }

    private String extractZip(byte[] bytes) throws IOException {
        StringBuilder text = new StringBuilder();
        long expanded = 0;
        int entries = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                if (++entries > MAX_ZIP_ENTRIES) throw new IllegalArgumentException("ZIP 文件数量超过 30 个");
                String ext = extension(entry.getName());
                if (!ALLOWED.contains(ext) || "zip".equals(ext)) continue;
                byte[] entryBytes = readEntry(zip, MAX_EXTRACTED_BYTES - expanded);
                expanded += entryBytes.length;
                text.append("\n\n--- 文件：").append(entry.getName()).append(" ---\n")
                        .append(extract(entryBytes, ext, 1));
                if (text.length() >= MAX_TEXT_CHARS) break;
            }
        }
        return text.toString();
    }

    private byte[] readEntry(ZipInputStream zip, long remaining) throws IOException {
        if (remaining <= 0) throw new IllegalArgumentException("ZIP 解压内容超过 24MB");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        long total = 0;
        while ((read = zip.read(buffer)) != -1) {
            total += read;
            if (total > remaining) throw new IllegalArgumentException("ZIP 解压内容超过 24MB");
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    public static boolean isImage(MultipartFile file) {
        return file != null && file.getContentType() != null
                && file.getContentType().toLowerCase(Locale.ROOT).startsWith("image/");
    }

    private static String extension(String name) {
        String value = name == null ? "" : name.toLowerCase(Locale.ROOT);
        int dot = value.lastIndexOf('.');
        return dot >= 0 && dot < value.length() - 1 ? value.substring(dot + 1) : "";
    }
}
