package com.visionary.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "visionary.demo-scenario", name = "seed-on-startup", havingValue = "true")
public class DemoScenarioStartupSeeder implements ApplicationRunner {

    private final DemoScenarioSeeder seeder;

    @Override
    public void run(ApplicationArguments args) {
        DemoScenarioSeeder.DemoScenarioResult result = seeder.seed();
        log.info("Demo scenario ready: student={}, admin={}, sessionId={}",
                result.studentUsername(), result.adminUsername(), result.learningSessionId());
    }
}
