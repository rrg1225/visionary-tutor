package com.visionary.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.visionary.agent.AgentResponse;
import com.visionary.agent.AgentTaskType;
import com.visionary.agent.RouterGateway;
import com.visionary.dto.AgentInvokeRequest;
import com.visionary.dto.AgentInvokeRequestSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * Unified AI agent invoke endpoint consumed by the frontend ({@code /api/ai/invoke}).
 */
@Slf4j
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiInvokeController {

    private final RouterGateway routerGateway;
    private final ObjectMapper objectMapper;

    @PostMapping("/invoke")
    public ResponseEntity<AgentResponse<?>> invoke(@RequestBody JsonNode body) {
        AgentInvokeRequest request = AgentInvokeRequestSupport.fromJson(body, objectMapper);
        log.debug("AI invoke: taskType={}, hasExt={}", request.taskType(), request.payloadExt() != null);
        return ResponseEntity.ok(routerGateway.dispatch(request));
    }

    @PostMapping(value = "/invoke", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<AgentResponse<?>> invokeMultipart(
            @RequestPart("request") JsonNode body,
            @RequestPart(value = "file", required = false) MultipartFile file
    ) {
        AgentInvokeRequest request = AgentInvokeRequestSupport.fromJson(body, objectMapper);
        return ResponseEntity.ok(routerGateway.dispatch(request, file));
    }

    @PostMapping("/route")
    public ResponseEntity<Map<String, String>> route(@RequestBody JsonNode body) {
        AgentInvokeRequest request = AgentInvokeRequestSupport.fromJson(body, objectMapper);
        AgentTaskType taskType = routerGateway.resolveRoute(request);
        return ResponseEntity.ok(Map.of("taskType", taskType.name()));
    }
}
