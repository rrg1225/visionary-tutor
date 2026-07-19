package com.visionary.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.visionary.agent.core.*;
import com.visionary.client.DeepSeekApiClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * ReviewAgent - final quality gate that produces a consolidated review report using LLM.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewAgent implements Agent {

    private final DeepSeekApiClient deepSeekApiClient;
    private final ObjectMapper objectMapper;

    @Override
    public String getRole() {
        return "ReviewAgent";
    }

    @Override
    public Set<String> getSupportedTools() {
        return Set.of();
    }

    @Override
    public AgentResult execute(AgentTask task, AgentContext context) {
        String runId = context.runId();
        Object specialistResults = task.input().getOrDefault("specialistResults", List.of());
        List<AgentResult> allResults = specialistResults instanceof List<?> rawList
                ? rawList.stream().filter(AgentResult.class::isInstance).map(AgentResult.class::cast).toList()
                : List.of();
        Map<?, ?> criticReports = (Map<?, ?>) task.input().getOrDefault("criticReports", Map.of());
        String topic = (String) task.input().getOrDefault("topic", "未知主题");

        if (!deepSeekApiClient.isConfigured()) {
            return new AgentResult(true, "Review completed (LLM not configured)", List.of(),
                    Map.of("summary", "LLM未配置，无法生成高质量审查报告"), List.of());
        }

        StringBuilder sb = new StringBuilder();
        sb.append("主题：").append(topic).append("\n");
        sb.append("Agent 执行结果汇总：\n");
        for (AgentResult r : allResults) {
            sb.append("- ").append(r.metadata().getOrDefault("artifactType", "UNKNOWN"))
              .append(" : ").append(r.success() ? "成功" : "失败").append("\n");
        }
        if (!criticReports.isEmpty()) {
            sb.append("Critic 审查摘要：\n");
            criticReports.forEach((agent, report) -> sb.append("- ").append(agent).append(": ").append(report).append("\n"));
        }

        String prompt = """
                请对以下多智能体资源生成流程进行最终审查并输出结构化报告：
                %s

                输出 JSON：
                {
                  "overallVerdict": "PASS | NEEDS_HUMAN_REVIEW",
                  "summary": "一句话总结本次生成质量",
                  "strengths": ["优点1", "优点2"],
                  "weaknesses": ["不足1", "不足2"],
                  "recommendations": ["建议1", "建议2"]
                }
                """.formatted(sb);

        try {
            String raw = deepSeekApiClient.chat(
                    "你是 ReviewAgent。负责对整个多智能体协作流程进行最终质量把关。只输出 JSON。",
                    prompt,
                    false
            );
            String json = extractJson(raw);
            JsonNode root = objectMapper.readTree(json);

            Map<String, Object> meta = new HashMap<>();
            meta.put("overallVerdict", root.path("overallVerdict").asText("PASS"));
            meta.put("summary", root.path("summary").asText(""));
            meta.put("strengths", toList(root.path("strengths")));
            meta.put("weaknesses", toList(root.path("weaknesses")));
            meta.put("recommendations", toList(root.path("recommendations")));

            AgentNegotiationProtocol.publishConsensus(
                    context,
                    root.path("summary").asText(""),
                    root.path("overallVerdict").asText("PASS")
            );

            return new AgentResult(true, "Final review completed", List.of(), meta, List.of());
        } catch (Exception e) {
            log.warn("ReviewAgent failed: {}", e.getMessage());
            return new AgentResult(true, "Review failed", List.of(),
                    Map.of("summary", "审查失败，建议人工复核"), List.of());
        }
    }

    private List<String> toList(JsonNode node) {
        List<String> list = new ArrayList<>();
        if (node.isArray()) {
            node.forEach(n -> list.add(n.asText()));
        }
        return list;
    }

    private String extractJson(String raw) {
        if (raw == null) return "{}";
        int s = raw.indexOf('{');
        int e = raw.lastIndexOf('}');
        return (s >= 0 && e > s) ? raw.substring(s, e + 1) : raw;
    }
}
