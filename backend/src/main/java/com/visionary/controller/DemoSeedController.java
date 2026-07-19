package com.visionary.controller;

import com.visionary.config.DemoScenarioSeeder;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/demo")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "visionary.demo-scenario", name = "seed-enabled", havingValue = "true")
public class DemoSeedController {

    private final DemoScenarioSeeder seeder;

    @Value("${visionary.demo-scenario.seed-token:}")
    private String seedToken;

    @PostMapping("/seed")
    public DemoScenarioSeeder.DemoScenarioResult seed(
            @RequestHeader(value = "X-Demo-Seed-Token", required = false) String token
    ) {
        if (seedToken == null || seedToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Demo seed token is not configured");
        }
        byte[] expected = seedToken.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] actual = token == null ? new byte[0] : token.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (!java.security.MessageDigest.isEqual(expected, actual)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid demo seed token");
        }
        return seeder.seed();
    }
}
