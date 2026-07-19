package com.visionary.controller;

import com.visionary.client.ProviderCircuitBreaker;
import com.visionary.config.AiApiConfig;
import com.visionary.rag.RagRetrievalService;
import com.visionary.rag.VectorDbService;
import com.visionary.service.LocalMockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@RestController
@RequiredArgsConstructor
public class HealthController {

    private final LocalMockService localMockService;
    private final AiApiConfig aiApiConfig;
    private final ProviderCircuitBreaker circuitBreaker;
    private final VectorDbService vectorDbService;
    private final RagRetrievalService ragRetrievalService;
    private final StringRedisTemplate redisTemplate;
    private final DataSource dataSource;

    @GetMapping({"/actuator/health", "/api/health"})
    public ResponseEntity<Map<String, Object>> health() {
        boolean dbAvailable = checkDbAvailable();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", dbAvailable ? "UP" : "DEGRADED");
        payload.put("service", "visionary-tutor-backend");
        // 粗粒度 RAG 就绪标志是 ai_engine/rag_eval.py 预检与 CI rag-eval 工作流的公开契约；
        // 详细依赖状态（db/redis/chroma/熔断器）仍只在管理员端点 /api/ops/health 暴露。
        payload.put("ragHaAvailable", ragRetrievalService.isAvailable());
        payload.put("timestamp", Instant.now().toString());
        return ResponseEntity.ok(payload);
    }

    /** Detailed dependency state is restricted to authenticated administrators. */
    @GetMapping("/api/ops/health")
    public ResponseEntity<Map<String, Object>> operationsHealth() {
        boolean dbAvailable = checkDbAvailable();
        boolean redisAvailable = checkRedisAvailable();
        boolean chromaAvailable = vectorDbService.isAvailable();
        boolean ragHaAvailable = ragRetrievalService.isAvailable();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", dbAvailable ? "UP" : "DEGRADED");
        payload.put("service", "visionary-tutor-backend");
        payload.put("demoMode", localMockService.isEnabled());
        payload.put("aiProviders", Map.of(
                "deepSeek", aiApiConfig.isDeepSeekConfigured(),
                "dashScope", aiApiConfig.isDashScopeConfigured(),
                "xunfeiSpark", aiApiConfig.isXunfeiConfigured()
        ));
        payload.put("dbAvailable", dbAvailable);
        payload.put("redisAvailable", redisAvailable);
        payload.put("chromaAvailable", chromaAvailable);
        payload.put("ragHaAvailable", ragHaAvailable);
        payload.put("circuitBreakers", circuitBreaker.snapshot());
        payload.put("timestamp", Instant.now().toString());
        return ResponseEntity.ok(payload);
    }

    @GetMapping("/api/ops/competition-readiness")
    public ResponseEntity<Map<String, Object>> competitionReadiness() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("target", "第十五届中国软件杯 A3：基于大模型的个性化资源生成与学习多智能体系统开发");
        payload.put("overallLevel", "国二冲刺，国一需补强评测数据与现场视频稳定性证据");
        payload.put("scoreFocus", Map.of(
                "innovationAndPracticality", "35%",
                "functionAndTechnicalFit", "45%",
                "documents", "10%",
                "demoVideoAndPpt", "10%"
        ));
        payload.put("coreRequirements", buildCoreRequirementReadiness());
        payload.put("awardGapActions", buildAwardGapActions());
        payload.put("demoRiskControls", buildDemoRiskControls());
        payload.put("submissionChecklist", buildSubmissionChecklist());
        payload.put("updatedAt", Instant.now().toString());
        return ResponseEntity.ok(payload);
    }

    private Map<String, Object> buildCoreRequirementReadiness() {
        Map<String, Object> readiness = new LinkedHashMap<>();
        readiness.put("dialogueProfile", Map.of(
                "status", "READY",
                "evidence", "7 维画像、行为校准、知识追踪、记忆审核"
        ));
        readiness.put("multiAgentResourceGeneration", Map.of(
                "status", "READY",
                "evidence", "Supervisor + Planner/Doc/Quiz/MindMap/Path/Code/Reading/Visual + Critic/Review"
        ));
        readiness.put("personalizedPathAndPush", Map.of(
                "status", "READY",
                "evidence", "持久化 DAG、前置约束、混合推荐、低分补救推送"
        ));
        readiness.put("multimodalTutoring", Map.of(
                "status", "READY_PLUS",
                "evidence", "SSE 答疑、导图图解、TTS、浏览器动画、作业视觉诊断"
        ));
        readiness.put("learningEffectAssessment", Map.of(
                "status", "READY_PLUS",
                "evidence", "前测/后测、掌握度雷达、学习报告、薄弱节点回写"
        ));
        readiness.put("antiHallucinationAndSafety", Map.of(
                "status", "READY",
                "evidence", "RAG grounding、CitationValidator、StrictFactCheck、Critic、ContentSafetyFilter、BM25 降级"
        ));
        return readiness;
    }

    private Map<String, Object> buildAwardGapActions() {
        Map<String, Object> actions = new LinkedHashMap<>();
        actions.put("goldMedal", "补 50-100 条 RAG 金标评测、真实用户试用数据、稳定的浏览器动画演示与录屏、突出科大讯飞生态接入说明");
        actions.put("silverMedal", "保持固定演示链路全绿，准备降级素材，PPT 强化评分项对照、工程可运行证据和创新价值闭环");
        actions.put("mustAvoid", "避免宣称完全无幻觉、成熟 BKT 或现场实时生成视频必成功；用可审计降级与示例结果表达工程边界");
        return actions;
    }

    private Map<String, Object> buildDemoRiskControls() {
        Map<String, Object> controls = new LinkedHashMap<>();
        controls.put("llmTimeout", "切换 legacy/hybrid 降级并展示已生成资源");
        controls.put("chromaUnavailable", "启用本地 BM25 高可用检索，前端显示降级提示");
        controls.put("animationUnavailable", "展示动画文字注解与静态关键步骤");
        controls.put("emptyReport", "使用答辩预览报告和固定 CNN 学习记录");
        controls.put("pptExportTimeout", "Premium 导出失败自动回退 Standard PPTX");
        return controls;
    }

    private Map<String, Object> buildSubmissionChecklist() {
        Map<String, Object> checklist = new LinkedHashMap<>();
        checklist.put("sourceCode", "项目源码、配置样例、数据库迁移、AI 引擎脚本已归档");
        checklist.put("knowledgeBase", "计算机视觉课程知识库、分层文档、RAG 评测脚本已准备");
        checklist.put("documents", "开发说明、测试说明、第三方组件与 AI Coding 工具说明需放在显著位置");
        checklist.put("ppt", "建议 25 页：背景、方案、功能、技术、评测、演示路线、风险边界、致谢");
        checklist.put("video", "7 分钟内：登录建档、CNN 问答、资源生成、路径、测评、报告、多模态辅导");
        return checklist;
    }

    private boolean checkDbAvailable() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(2);
        } catch (Exception e) {
            log.debug("DB health check failed: {}", e.getMessage());
            return false;
        }
    }

    private boolean checkRedisAvailable() {
        try {
            String pong = redisTemplate.execute((RedisCallback<String>) connection -> connection.ping());
            return pong != null && !pong.isBlank();
        } catch (Exception e) {
            log.debug("Redis health check failed: {}", e.getMessage());
            return false;
        }
    }
}
