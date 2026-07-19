package com.visionary.config;

import com.visionary.service.ShowcaseContentSeedService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 启动时注入示例教材与示例资源（空库时执行一次）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "visionary.showcase", name = "seed-on-startup", havingValue = "true", matchIfMissing = true)
public class ShowcaseContentSeedRunner implements ApplicationRunner {

    private final ShowcaseContentSeedService showcaseContentSeedService;

    @Override
    public void run(ApplicationArguments args) {
        try {
            showcaseContentSeedService.seedIfEmpty();
        } catch (Exception e) {
            log.warn("[showcase-seed] 示例内容注入失败（不影响主流程）: {}", e.getMessage());
        }
    }
}
