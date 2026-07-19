package com.visionary.config;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * 从 backend/.env.properties（或项目根 .env.properties）加载本地密钥。
 * 开发环境下该文件优先于 IDE 里空的 OS 环境变量（常见导致 Key「写了但不生效」）。
 */
@Slf4j
public final class LocalEnvFileLoader {

    private LocalEnvFileLoader() {
    }

    public static Path findEnvFile() {
        List<Path> candidates = new ArrayList<>();
        String userDir = System.getProperty("user.dir", ".");

        candidates.add(Path.of(userDir, "backend", ".env.properties"));
        candidates.add(Path.of(userDir, ".env.properties"));
        candidates.add(Path.of("backend", ".env.properties"));
        candidates.add(Path.of(".env.properties"));

        Path current = Path.of(userDir).toAbsolutePath().normalize();
        for (int i = 0; i < 4 && current != null; i++) {
            candidates.add(current.resolve("backend").resolve(".env.properties"));
            candidates.add(current.resolve(".env.properties"));
            current = current.getParent();
        }

        return candidates.stream()
                .map(Path::normalize)
                .filter(Files::isRegularFile)
                .findFirst()
                .orElse(null);
    }

    public static Properties load(Path envFile) {
        Properties properties = new Properties();
        if (envFile == null) {
            return properties;
        }
        try (Reader reader = Files.newBufferedReader(envFile, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException e) {
            log.warn("Failed to read {}: {}", envFile, e.getMessage());
        }
        return properties;
    }

    public static Properties loadFirstAvailable() {
        Path envFile = findEnvFile();
        if (envFile != null) {
            log.info("[env] Using local secrets file: {}", envFile.toAbsolutePath().normalize());
        } else {
            log.warn("[env] No .env.properties found — AI keys must come from OS environment variables");
        }
        return load(envFile);
    }

    /**
     * 将非空条目写入 System properties（启动极早阶段，供 Spring 占位符解析）。
     */
    public static void applyToSystemProperties(Properties properties) {
        properties.forEach((rawKey, rawValue) -> {
            String name = normalizeKey(String.valueOf(rawKey));
            String value = String.valueOf(rawValue).trim();
            if (name.isBlank() || value.isBlank()) {
                return;
            }
            System.setProperty(name, value);
        });
    }

    public static String normalizeKey(String key) {
        return key.replace("\uFEFF", "").replace("ï»¿", "").trim();
    }
}
