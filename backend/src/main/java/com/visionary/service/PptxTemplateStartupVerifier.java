package com.visionary.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.visionary.config.PptxProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Map;

/**
 * 应用启动时校验 Premium 模板三件套 SHA-256。
 * 校验失败仅 log.warn，不阻断启动（运行时 premium 会 fallback）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PptxTemplateStartupVerifier {

    private final PptxProperties pptxProperties;
    private final ObjectMapper objectMapper;

    @EventListener(ApplicationReadyEvent.class)
    public void verifyOnStartup() {
        if (!pptxProperties.getPremium().isEnabled() || !pptxProperties.getTemplate().isStartupVerify()) {
            log.info("[pptx] 模板启动校验已跳过 (premium.enabled 或 template.startup-verify=false)");
            return;
        }

        try {
            Path templateDir = Path.of(pptxProperties.getTemplate().getDir()).toAbsolutePath().normalize();
            String baseName = pptxProperties.getTemplate().getBaseName();
            Path manifestPath = templateDir.resolve(baseName + ".manifest.json");

            if (!Files.exists(manifestPath)) {
                log.warn("[pptx] 模板 manifest 不存在: {} — premium 导出将 fallback", manifestPath);
                return;
            }

            JsonNode manifest = objectMapper.readTree(Files.readString(manifestPath));
            JsonNode files = manifest.get("files");
            if (files == null || !files.isObject()) {
                log.warn("[pptx] manifest.files 格式错误 — premium 导出将 fallback");
                return;
            }

            Iterator<Map.Entry<String, JsonNode>> it = files.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> entry = it.next();
                String key = entry.getKey();
                JsonNode info = entry.getValue();
                String expectedSha = info.path("sha256").asText("");
                Path filePath = templateDir.resolve(baseName + suffixForKey(key));

                if (!Files.exists(filePath)) {
                    log.warn("[pptx] 模板文件缺失: {} — premium 将 fallback", filePath);
                    return;
                }
                String actualSha = sha256(filePath);
                if (!expectedSha.equals(actualSha)) {
                    log.warn("[pptx] 模板 SHA 不匹配 [{}]: 期望 {} 实际 {} — premium 将 fallback",
                            key, abbrev(expectedSha), abbrev(actualSha));
                    return;
                }
            }

            log.info("[pptx] Premium 模板校验通过 (v{})", manifest.path("templateVersion").asText("unknown"));
        } catch (Exception e) {
            log.warn("[pptx] 模板启动校验异常: {} — premium 将 fallback", e.getMessage());
        }
    }

    private static String suffixForKey(String key) {
        return switch (key) {
            case "pptx" -> ".pptx";
            case "slots" -> ".slots.json";
            case "mapping" -> ".mapping.json";
            default -> "." + key;
        };
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(path)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                digest.update(buf, 0, n);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String abbrev(String sha) {
        return sha == null || sha.length() < 8 ? sha : sha.substring(0, 8) + "...";
    }
}
