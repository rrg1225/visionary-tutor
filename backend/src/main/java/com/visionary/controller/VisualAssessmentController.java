package com.visionary.controller;

import com.visionary.agent.AgentResponse;
import com.visionary.agent.AgentTaskType;
import com.visionary.agent.RouterGateway;
import com.visionary.dto.AgentInvokeRequest;
import com.visionary.dto.VisualAssessmentRequest;
import com.visionary.service.DocumentAssessmentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * 视觉测评 Controller：手写草稿上传与 AI 自动批阅接口。
 *
 * <p>提供两种调用方式：</p>
 * <ul>
 *   <li><b>简易模式</b>：单文件上传，自动触发测评</li>
 *   <li><b>高级模式</b>：支持自定义提示词、多文件、上下文关联</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/assessment")
@RequiredArgsConstructor
public class VisualAssessmentController {

    private final RouterGateway routerGateway;
    private final DocumentAssessmentService documentAssessmentService;

    /**
     * 简易上传测评接口（单文件，自动触发 AI 批阅）。
     *
     * <p>使用场景：AssessmentFillView 上传作业图片后立即批阅</p>
     *
     * @param file   手写草稿/报错截图（支持 JPG/PNG/GIF/WebP，最大 10MB）
     * @param prompt 批阅提示词（可选，如 "请批阅这道矩阵计算题"）
     * @return AI 批阅结果，包含 OCR 文本、错误分析、改进建议
     */
    @PostMapping(value = "/upload-and-assess", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<AgentResponse<?>> uploadAndAssess(
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "prompt", required = false, defaultValue = "") String prompt
    ) throws IOException {
        log.info("接收到视觉测评请求，文件名: {}, 大小: {} bytes", file.getOriginalFilename(), file.getSize());

        if (!DocumentAssessmentService.isImage(file)) {
            return ResponseEntity.ok(documentAssessmentService.assess(file, prompt));
        }

        Map<String, Object> payloadExt = new HashMap<>();
        payloadExt.put("ragQuery", prompt);

        AgentInvokeRequest request = new AgentInvokeRequest(
                AgentTaskType.VISUAL_ASSESSMENT,
                prompt,
                null,
                payloadExt
        );

        AgentResponse<?> response = routerGateway.dispatch(request, file);

        log.info("视觉测评完成，响应状态: {}", response.getStatus());

        return ResponseEntity.ok(response);
    }

    /**
     * 通过已有 URL 进行测评（不重新上传）。
     *
     * <p>使用场景：图片已在外部托管，或复用之前上传的图片</p>
     *
     * @param request 包含 imageUrl 和可选的 prompt
     * @return AI 批阅结果
     */
    @PostMapping("/assess-by-url")
    public ResponseEntity<AgentResponse<?>> assessByUrl(
            @RequestBody VisualAssessmentRequest request
    ) {
        log.info("接收到 URL 测评请求，URL: {}", request.imageUrl());

        Map<String, Object> payloadExt = new HashMap<>();
        payloadExt.put("imageUrl", request.imageUrl());
        payloadExt.put("ragQuery", request.prompt());

        AgentInvokeRequest invokeRequest = new AgentInvokeRequest(
                AgentTaskType.VISUAL_ASSESSMENT,
                request.prompt(),
                null,
                payloadExt
        );

        AgentResponse<?> response = routerGateway.dispatch(invokeRequest);

        return ResponseEntity.ok(response);
    }
}
