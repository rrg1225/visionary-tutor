package com.visionary.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * 将 .env.properties 置于 Spring Environment 最高优先级，
 * 避免 IDE Run Configuration 里的空 DEEPSEEK_API_KEY / ZHIPU_API_KEY 覆盖本地文件。
 */
public class LocalEnvPropertiesEnvironmentPostProcessor implements EnvironmentPostProcessor {

    static final String PROPERTY_SOURCE_NAME = "localEnvProperties";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Path envFile = LocalEnvFileLoader.findEnvFile();
        Properties properties = LocalEnvFileLoader.load(envFile);
        if (properties.isEmpty()) {
            return;
        }

        Map<String, Object> map = new HashMap<>();
        properties.forEach((rawKey, rawValue) -> {
            String name = LocalEnvFileLoader.normalizeKey(String.valueOf(rawKey));
            String value = String.valueOf(rawValue).trim();
            if (!name.isBlank() && !value.isBlank()) {
                map.put(name, value);
            }
        });
        if (map.isEmpty()) {
            return;
        }

        PropertySource<?> source = new MapPropertySource(PROPERTY_SOURCE_NAME, map);
        environment.getPropertySources().addFirst(source);
    }
}
