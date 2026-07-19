package com.visionary.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.visionary.entity.GeneratedArtifact;
import com.visionary.entity.LearningPathEdge;
import com.visionary.entity.LearningPathNode;
import com.visionary.repository.LearningPathEdgeRepository;
import com.visionary.repository.LearningPathNodeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class LearningPathGraphService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ObjectMapper objectMapper;
    private final LearningPathNodeRepository nodeRepository;
    private final LearningPathEdgeRepository edgeRepository;
    private final LearningPathStepService learningPathStepService;

    public String graphJsonFromPlan(LearningPathRePlanService.LearningPathPlan plan) {
        List<PathNode> nodes = new ArrayList<>();
        List<PathEdge> edges = new ArrayList<>();
        int order = 1;
        for (LearningPathRePlanService.PathStep step : plan.steps()) {
            String key = "n" + order;
            nodes.add(new PathNode(
                    key,
                    step.concept(),
                    step.recommendedResourceType(),
                    step.currentMastery(),
                    step.estimatedMinutes(),
                    order,
                    step.rationale()
            ));
            if (order > 1) {
                edges.add(new PathEdge("n" + (order - 1), key, "PREREQUISITE", "learn-before", order - 1));
            }
            order++;
        }
        return toGraphJson(plan.topic(), nodes, edges, plan.overallRationale(), plan.estimatedHours(), plan.nextMilestone());
    }

    public String graphJsonFromMarkdown(Long sessionId, String title, String markdown) {
        List<String> labels = parseStepLabels(markdown);
        if (labels.isEmpty()) {
            labels = List.of(title == null || title.isBlank() ? "Learning goal" : title);
        }
        List<PathNode> nodes = new ArrayList<>();
        List<PathEdge> edges = new ArrayList<>();
        for (int i = 0; i < labels.size(); i++) {
            String key = "n" + (i + 1);
            nodes.add(new PathNode(key, labels.get(i), "", null, null, i + 1, ""));
            if (i > 0) {
                edges.add(new PathEdge("n" + i, key, "PREREQUISITE", "learn-before", i));
            }
        }
        return toGraphJson(title, nodes, edges, "Parsed from generated learning path markdown.", null, "");
    }

    @Transactional
    public void persistGraph(GeneratedArtifact artifact, String graphJson) {
        if (artifact == null || artifact.getId() == null || graphJson == null || graphJson.isBlank()) {
            return;
        }

        List<LearningPathNode> nodes = new ArrayList<>();
        List<LearningPathEdge> edges = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(graphJson);
            if (!validateDag(root)) {
                log.warn("Learning path DAG persistence skipped for artifact {}: invalid or cyclic graph",
                        artifact.getId());
                return;
            }

            int nodeOrder = 1;
            for (var nodeJson : root.path("nodes")) {
                LearningPathNode node = new LearningPathNode();
                node.setArtifactId(artifact.getId());
                node.setLearningSessionId(artifact.getLearningSessionId());
                node.setNodeKey(nodeJson.path("id").asText("n" + nodeOrder));
                node.setLabel(nodeJson.path("label").asText("Step " + nodeOrder));
                node.setResourceType(nodeJson.path("resourceType").asText(""));
                node.setMastery(nodeJson.hasNonNull("mastery") ? nodeJson.path("mastery").asInt() : null);
                node.setEstimatedMinutes(nodeJson.hasNonNull("estimatedMinutes") ? nodeJson.path("estimatedMinutes").asInt() : null);
                node.setOrderIndex(nodeJson.path("order").asInt(nodeOrder));
                node.setMetadataJson(objectMapper.writeValueAsString(Map.of(
                        "rationale", nodeJson.path("rationale").asText("")
                )));
                nodes.add(node);
                nodeOrder++;
            }

            int edgeOrder = 1;
            for (var edgeJson : root.path("edges")) {
                LearningPathEdge edge = new LearningPathEdge();
                edge.setArtifactId(artifact.getId());
                edge.setLearningSessionId(artifact.getLearningSessionId());
                edge.setFromNodeKey(edgeJson.path("from").asText());
                edge.setToNodeKey(edgeJson.path("to").asText());
                edge.setRelationType(edgeJson.path("type").asText("PREREQUISITE"));
                edge.setLabel(edgeJson.path("label").asText(""));
                edge.setOrderIndex(edgeJson.path("order").asInt(edgeOrder));
                edges.add(edge);
                edgeOrder++;
            }
        } catch (JsonProcessingException e) {
            log.warn("Learning path DAG persistence skipped for artifact {}: {}", artifact.getId(), e.getMessage());
            return;
        }

        edgeRepository.deleteByArtifactId(artifact.getId());
        nodeRepository.deleteByArtifactId(artifact.getId());
        nodeRepository.saveAll(nodes);
        edgeRepository.saveAll(edges);
        learningPathStepService.syncStepsFromArtifact(artifact);
    }

    /**
     * 解析 Agent 输出的 JSON 字符串并保存到数据库。
     * 打通 DAG 数据库闭环，安全反序列化，失败时优雅降级。
     *
     * @param agentJsonOutput PathAgent 输出的 JSON 字符串（必须包含 nodes 和 edges 数组）
     * @param sessionId       学习会话ID
     */
    @Transactional
    public void parseAndSavePathDag(String agentJsonOutput, Long sessionId) {
        if (agentJsonOutput == null || agentJsonOutput.isBlank() || sessionId == null) {
            log.warn("[parseAndSavePathDag] 参数无效: agentJsonOutput={}, sessionId={}",
                    agentJsonOutput == null ? "null" : "blank", sessionId);
            return;
        }

        try {
            // 使用 Spring 默认自带的 ObjectMapper (Jackson) 解析 JSON
            com.fasterxml.jackson.databind.JsonNode rootNode = objectMapper.readTree(agentJsonOutput);

            // 校验 DAG 是否有效（无环）
            if (!validateDag(rootNode)) {
                log.error("[parseAndSavePathDag] DAG 验证失败，检测到环路，sessionId={}", sessionId);
                return;
            }

            // 解析并保存节点
            com.fasterxml.jackson.databind.JsonNode nodesArray = rootNode.path("nodes");
            if (nodesArray.isMissingNode() || !nodesArray.isArray()) {
                log.error("[parseAndSavePathDag] JSON 中缺少 nodes 数组或格式不正确, sessionId={}", sessionId);
                return;
            }

            List<LearningPathNode> nodesToSave = new ArrayList<>();
            int nodeOrder = 1;

            for (com.fasterxml.jackson.databind.JsonNode nodeJson : nodesArray) {
                LearningPathNode node = new LearningPathNode();
                node.setLearningSessionId(sessionId);

                // 支持多种可能的字段名：id / nodeKey
                String nodeId = nodeJson.has("id") ? nodeJson.get("id").asText("n" + nodeOrder)
                        : nodeJson.path("nodeKey").asText("n" + nodeOrder);
                node.setNodeKey(nodeId);

                // 支持 label / topic / title 字段
                String label = nodeJson.has("label") ? nodeJson.get("label").asText("Step " + nodeOrder)
                        : (nodeJson.has("topic") ? nodeJson.get("topic").asText("Topic " + nodeOrder)
                        : nodeJson.path("title").asText("Step " + nodeOrder));
                node.setLabel(label);

                // 可选字段
                node.setResourceType(nodeJson.path("resourceType").asText(""));
                if (nodeJson.has("estimatedMinutes") && nodeJson.get("estimatedMinutes").isNumber()) {
                    node.setEstimatedMinutes(nodeJson.get("estimatedMinutes").asInt());
                }
                if (nodeJson.has("mastery") && nodeJson.get("mastery").isNumber()) {
                    node.setMastery(nodeJson.get("mastery").asInt());
                }

                node.setOrderIndex(nodeOrder);

                // 将额外元数据存入 metadataJson
                Map<String, Object> metadata = new HashMap<>();
                if (nodeJson.has("rationale")) {
                    metadata.put("rationale", nodeJson.get("rationale").asText(""));
                }
                if (nodeJson.has("description")) {
                    metadata.put("description", nodeJson.get("description").asText(""));
                }
                node.setMetadataJson(objectMapper.writeValueAsString(metadata));

                nodesToSave.add(node);
                nodeOrder++;
            }

            // 批量保存节点
            nodeRepository.saveAll(nodesToSave);
            log.info("[parseAndSavePathDag] 成功保存 {} 个节点, sessionId={}", nodesToSave.size(), sessionId);

            // 解析并保存边
            com.fasterxml.jackson.databind.JsonNode edgesArray = rootNode.path("edges");
            if (edgesArray.isMissingNode() || !edgesArray.isArray()) {
                log.warn("[parseAndSavePathDag] JSON 中缺少 edges 数组, sessionId={}", sessionId);
                return;
            }

            List<LearningPathEdge> edgesToSave = new ArrayList<>();
            int edgeOrder = 1;

            for (com.fasterxml.jackson.databind.JsonNode edgeJson : edgesArray) {
                LearningPathEdge edge = new LearningPathEdge();
                edge.setLearningSessionId(sessionId);

                // 支持 source/from 和 target/to 字段名
                String sourceKey = edgeJson.has("source") ? edgeJson.get("source").asText("")
                        : edgeJson.path("from").asText("");
                String targetKey = edgeJson.has("target") ? edgeJson.get("target").asText("")
                        : edgeJson.path("to").asText("");

                // 验证节点引用存在性
                if (sourceKey.isBlank() || targetKey.isBlank()) {
                    log.warn("[parseAndSavePathDag] 边缺少 source/target, 跳过此边");
                    continue;
                }

                edge.setFromNodeKey(sourceKey);
                edge.setToNodeKey(targetKey);

                // 关系类型和标签
                String relationType = edgeJson.has("relation") ? edgeJson.get("relation").asText("PREREQUISITE")
                        : edgeJson.path("type").asText("PREREQUISITE");
                edge.setRelationType(relationType);
                edge.setLabel(edgeJson.path("label").asText(""));
                edge.setOrderIndex(edgeOrder);

                edgesToSave.add(edge);
                edgeOrder++;
            }

            // 批量保存边
            edgeRepository.saveAll(edgesToSave);
            log.info("[parseAndSavePathDag] 成功保存 {} 条边, sessionId={}", edgesToSave.size(), sessionId);

        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.error("[parseAndSavePathDag] JSON 解析异常, sessionId={}: {}", sessionId, e.getMessage());
        } catch (org.springframework.dao.DataAccessException e) {
            log.error("[parseAndSavePathDag] 数据库访问异常, sessionId={}: {}", sessionId, e.getMessage());
        } catch (Exception e) {
            // 【安全保障】：捕获所有异常，log.error 记录错误，优雅降级退出
            log.error("[parseAndSavePathDag] 未知异常, sessionId={}, 异常类型={}, 消息={}",
                    sessionId, e.getClass().getSimpleName(), e.getMessage());
        }
    }

    public String mergeGraphIntoContentJson(String existingJson, String graphJson) {
        try {
            Map<String, Object> payload = existingJson == null || existingJson.isBlank()
                    ? new LinkedHashMap<>()
                    : objectMapper.convertValue(objectMapper.readTree(existingJson), MAP_TYPE);
            payload.put("dag", true);
            payload.put("graph", objectMapper.readValue(graphJson, MAP_TYPE));
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            try {
                return objectMapper.writeValueAsString(Map.of(
                        "dag", true,
                        "graph", objectMapper.readValue(graphJson, MAP_TYPE)
                ));
            } catch (Exception ignored) {
                return existingJson;
            }
        }
    }

    private String toGraphJson(
            String topic,
            List<PathNode> nodes,
            List<PathEdge> edges,
            String rationale,
            Double estimatedHours,
            String nextMilestone
    ) {
        try {
            Map<String, Object> graph = new LinkedHashMap<>();
            graph.put("version", "1.0");
            graph.put("type", "learning_path_dag");
            graph.put("topic", topic);
            graph.put("nodes", nodes);
            graph.put("edges", edges);
            graph.put("rationale", rationale);
            graph.put("estimatedHours", estimatedHours);
            graph.put("nextMilestone", nextMilestone);
            graph.put("mermaid", toMermaid(nodes, edges));
            if (!validateDag(objectMapper.valueToTree(graph))) {
                throw new IllegalStateException("generated graph is cyclic");
            }
            return objectMapper.writeValueAsString(graph);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to build learning path DAG", e);
        }
    }

    private String toMermaid(List<PathNode> nodes, List<PathEdge> edges) {
        StringBuilder sb = new StringBuilder("flowchart TD\n");
        for (PathNode node : nodes) {
            sb.append("  ").append(node.id()).append("[\"")
                    .append(escapeMermaid(node.label()))
                    .append("\"]\n");
        }
        for (PathEdge edge : edges) {
            sb.append("  ").append(edge.from()).append(" --> ").append(edge.to()).append("\n");
        }
        return sb.toString();
    }

    private boolean validateDag(com.fasterxml.jackson.databind.JsonNode graph) {
        Map<String, Integer> indegree = new HashMap<>();
        Map<String, List<String>> outgoing = new HashMap<>();
        for (var node : graph.path("nodes")) {
            String id = node.path("id").asText();
            if (!id.isBlank()) {
                indegree.putIfAbsent(id, 0);
                outgoing.putIfAbsent(id, new ArrayList<>());
            }
        }
        for (var edge : graph.path("edges")) {
            String from = edge.path("from").asText();
            String to = edge.path("to").asText();
            if (!indegree.containsKey(from) || !indegree.containsKey(to)) {
                return false;
            }
            outgoing.get(from).add(to);
            indegree.put(to, indegree.get(to) + 1);
        }
        ArrayDeque<String> ready = new ArrayDeque<>();
        indegree.forEach((id, degree) -> {
            if (degree == 0) {
                ready.add(id);
            }
        });
        Set<String> visited = new HashSet<>();
        while (!ready.isEmpty()) {
            String id = ready.removeFirst();
            visited.add(id);
            for (String to : outgoing.getOrDefault(id, List.of())) {
                int degree = indegree.get(to) - 1;
                indegree.put(to, degree);
                if (degree == 0) {
                    ready.add(to);
                }
            }
        }
        return visited.size() == indegree.size();
    }

    private List<String> parseStepLabels(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return List.of();
        }
        List<String> labels = new ArrayList<>();
        for (String rawLine : markdown.split("\\R")) {
            String line = rawLine.trim()
                    .replaceFirst("^[-*]\\s+", "")
                    .replaceFirst("^\\d+[.)、]\\s*", "")
                    .replace("**", "")
                    .trim();
            if (line.isBlank() || line.startsWith("#") || line.startsWith(">") || line.startsWith("```")) {
                continue;
            }
            if (line.length() > 120) {
                line = line.substring(0, 120);
            }
            labels.add(line);
            if (labels.size() >= 8) {
                break;
            }
        }
        return labels;
    }

    private String escapeMermaid(String value) {
        return value == null ? "" : value.replace("\"", "\\\"");
    }

    public record PathNode(
            String id,
            String label,
            String resourceType,
            Integer mastery,
            Integer estimatedMinutes,
            int order,
            String rationale
    ) {}

    public record PathEdge(String from, String to, String type, String label, int order) {}

    /**
     * Loads executable DAG nodes/edges persisted for a learning-path artifact.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> loadPersistedGraph(Long artifactId) {
        if (artifactId == null) {
            return Map.of();
        }
        List<LearningPathNode> dbNodes = nodeRepository.findByArtifactIdOrderByOrderIndexAsc(artifactId);
        if (dbNodes.isEmpty()) {
            return Map.of();
        }
        List<LearningPathEdge> dbEdges = edgeRepository.findByArtifactIdOrderByOrderIndexAsc(artifactId);
        List<PathNode> nodes = dbNodes.stream()
                .map(node -> new PathNode(
                        node.getNodeKey(),
                        node.getLabel(),
                        node.getResourceType(),
                        node.getMastery(),
                        node.getEstimatedMinutes(),
                        node.getOrderIndex(),
                        extractNodeRationale(node.getMetadataJson())
                ))
                .toList();
        List<PathEdge> edges = dbEdges.stream()
                .map(edge -> new PathEdge(
                        edge.getFromNodeKey(),
                        edge.getToNodeKey(),
                        edge.getRelationType(),
                        edge.getLabel(),
                        edge.getOrderIndex()
                ))
                .toList();

        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("version", "1.0");
        graph.put("type", "learning_path_dag");
        graph.put("executable", true);
        graph.put("artifactId", artifactId);
        graph.put("nodes", nodes);
        graph.put("edges", edges);
        graph.put("mermaid", toMermaid(nodes, edges));
        return Map.of("dag", true, "graph", graph);
    }

    private String extractNodeRationale(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return "";
        }
        try {
            return objectMapper.readTree(metadataJson).path("rationale").asText("");
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Dynamic DAG failure handler.
     * When a node (e.g. "二维卷积") fails, reverse-traverse prerequisite edges,
     * mark ancestors NEEDS_REINFORCEMENT and downstream BLOCKED using metadataJson status.
     * All updates use BaseEntity gmt_created / gmt_modified via JPA auditing.
     * Returns ReplanResult carrying affected context for PathAgent local re-plan.
     */
    @Transactional
    public ReplanTriggerService.ReplanResult handleNodeFailure(
            Long learningSessionId,
            Long artifactId,
            String currentFailedNodeKey
    ) {
        if (learningSessionId == null || artifactId == null
                || currentFailedNodeKey == null || currentFailedNodeKey.isBlank()) {
            return new ReplanTriggerService.ReplanResult(
                    false,
                    "Invalid learningSessionId, artifactId or failed node key"
            );
        }

        List<LearningPathNode> allNodes = nodeRepository.findByArtifactIdOrderByOrderIndexAsc(artifactId);
        LearningPathNode failedNode = allNodes.stream()
                .filter(node -> learningSessionId.equals(node.getLearningSessionId()))
                .filter(node -> currentFailedNodeKey.equals(node.getNodeKey()))
                .findFirst()
                .orElse(null);
        if (failedNode == null) {
            return new ReplanTriggerService.ReplanResult(
                    false,
                    "Failed node not found in artifact " + artifactId + " for session " + learningSessionId
            );
        }

        allNodes = allNodes.stream()
                .filter(node -> learningSessionId.equals(node.getLearningSessionId()))
                .toList();
        List<LearningPathEdge> allEdges = edgeRepository.findByArtifactIdOrderByOrderIndexAsc(artifactId).stream()
                .filter(edge -> learningSessionId.equals(edge.getLearningSessionId()))
                .toList();

        // Build adjacency for reverse (prereq) and forward traversal
        Map<String, List<String>> incoming = new HashMap<>(); // to -> list of from (prereqs)
        Map<String, List<String>> outgoing = new HashMap<>(); // from -> list of to (dependents)
        for (LearningPathNode n : allNodes) {
            incoming.putIfAbsent(n.getNodeKey(), new ArrayList<>());
            outgoing.putIfAbsent(n.getNodeKey(), new ArrayList<>());
        }
        for (LearningPathEdge e : allEdges) {
            incoming.computeIfAbsent(e.getToNodeKey(), k -> new ArrayList<>()).add(e.getFromNodeKey());
            outgoing.computeIfAbsent(e.getFromNodeKey(), k -> new ArrayList<>()).add(e.getToNodeKey());
        }

        // Reverse BFS: collect all ancestor prereqs (including indirect)
        Set<String> reinforcementKeys = new LinkedHashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        queue.add(currentFailedNodeKey);
        Set<String> visited = new HashSet<>();
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            if (!visited.add(current)) continue;
            List<String> prereqs = incoming.getOrDefault(current, List.of());
            for (String p : prereqs) {
                reinforcementKeys.add(p);
                queue.add(p);
            }
        }
        reinforcementKeys.remove(currentFailedNodeKey); // the failed node itself is not a prereq

        // Forward: collect nodes that depend on the failed node (blocked path)
        Set<String> blockedKeys = new LinkedHashSet<>();
        queue.clear();
        queue.add(currentFailedNodeKey);
        visited.clear();
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            if (!visited.add(current)) continue;
            for (String dep : outgoing.getOrDefault(current, List.of())) {
                if (!reinforcementKeys.contains(dep)) {
                    blockedKeys.add(dep);
                }
                queue.add(dep);
            }
        }

        // Update DB: set status in metadataJson for affected nodes (non-destructive to entity)
        Map<String, LearningPathNode> nodeMap = allNodes.stream()
                .collect(java.util.stream.Collectors.toMap(
                        LearningPathNode::getNodeKey,
                        node -> node,
                        (first, ignored) -> first,
                        LinkedHashMap::new
                ));

        List<LearningPathNode> toSave = new ArrayList<>();
        for (String key : reinforcementKeys) {
            LearningPathNode node = nodeMap.get(key);
            if (node != null) {
                updateNodeStatus(node, "NEEDS_REINFORCEMENT");
                toSave.add(node);
            }
        }
        for (String key : blockedKeys) {
            LearningPathNode node = nodeMap.get(key);
            if (node != null) {
                updateNodeStatus(node, "BLOCKED");
                toSave.add(node);
            }
        }
        // Mark the failed node itself
        updateNodeStatus(failedNode, "FAILED");
        toSave.add(failedNode);

        if (!toSave.isEmpty()) {
            nodeRepository.saveAll(toSave);
        }

        // Re-package affected DAG context for PathAgent re-plan trigger
        List<String> affected = new ArrayList<>();
        affected.addAll(reinforcementKeys);
        affected.addAll(blockedKeys);
        affected.add(currentFailedNodeKey);

        String message = "DAG failure processed: session=" + learningSessionId
                + ", artifact=" + artifactId
                + ", node=" + currentFailedNodeKey
                + " -> reinforcement=" + reinforcementKeys + ", blocked=" + blockedKeys;

        log.info("[DAG] {}", message);

        // The actual PathAgent invocation (local re-plan + incremental material generation + DB write)
        // is performed by the caller using the returned context. Here we return success with summary.
        return new ReplanTriggerService.ReplanResult(true, message);
    }

    private void updateNodeStatus(LearningPathNode node, String status) {
        try {
            Map<String, Object> meta = new HashMap<>();
            if (node.getMetadataJson() != null && !node.getMetadataJson().isBlank()) {
                meta = objectMapper.readValue(node.getMetadataJson(), MAP_TYPE);
            }
            meta.put("status", status);
            meta.put("statusUpdatedAt", java.time.Instant.now().toString());
            node.setMetadataJson(objectMapper.writeValueAsString(meta));
        } catch (Exception e) {
            // fallback: simple status injection
            node.setMetadataJson("{\"status\":\"" + status + "\"}");
        }
    }
}
