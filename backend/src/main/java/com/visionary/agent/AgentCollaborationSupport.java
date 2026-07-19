package com.visionary.agent;



import com.visionary.agent.core.AgentResult;

import com.visionary.agent.core.SharedBlackboard;



import java.util.ArrayList;

import java.util.LinkedHashMap;

import java.util.List;

import java.util.Map;



/**

 * Builds cross-agent context from SharedBlackboard so downstream agents

 * consume upstream specialist outputs instead of working in isolation.

 */

public final class AgentCollaborationSupport {



    public static final String PEER_SUMMARIES_KEY = "peer_summaries";

    public static final String PEER_OUTLINES_KEY = "peer_outlines";



    private AgentCollaborationSupport() {

    }



    public static void publishPeerSummaries(SharedBlackboard blackboard, List<String> roles) {

        Map<String, String> summaries = new LinkedHashMap<>();

        for (String role : roles) {

            Object stored = blackboard.get(role + "_result");

            if (stored instanceof AgentResult result && result.success()) {

                summaries.put(role, summarize(result.output()));

            }

        }

        blackboard.put(PEER_SUMMARIES_KEY, summaries);

        blackboard.addTrace(new SharedBlackboard.AgentRunTrace(

                "Supervisor",

                "collaboration",

                "Published peer summaries for: " + String.join(", ", summaries.keySet()),

                java.time.Instant.now()

        ));

    }



    public static void publishPeerOutlines(SharedBlackboard blackboard, List<String> roles) {

        Map<String, String> outlines = new LinkedHashMap<>();

        for (String role : roles) {

            Object stored = blackboard.get(role + "_outline");

            if (stored instanceof String outline && !outline.isBlank()) {

                outlines.put(role, outline);

                continue;

            }

            Object resultStored = blackboard.get(role + "_outline_result");

            if (resultStored instanceof AgentResult result && result.success()) {

                outlines.put(role, summarize(result.output()));

            }

        }

        blackboard.put(PEER_OUTLINES_KEY, outlines);

        blackboard.addTrace(new SharedBlackboard.AgentRunTrace(

                "Supervisor",

                "negotiation",

                "Published peer outlines for: " + String.join(", ", outlines.keySet()),

                java.time.Instant.now()

        ));

    }



    public static String negotiationContextBlock(SharedBlackboard blackboard, String consumerRole) {

        StringBuilder builder = new StringBuilder();

        String outlines = peerOutlineBlock(blackboard, consumerRole);

        String summaries = peerContextBlock(blackboard, consumerRole);

        if (!outlines.isBlank()) {

            builder.append(outlines);

        }

        if (!summaries.isBlank()) {

            builder.append(summaries);

        }

        return builder.toString();

    }



    public static String peerOutlineBlock(SharedBlackboard blackboard, String consumerRole) {

        Object raw = blackboard.get(PEER_OUTLINES_KEY);

        if (!(raw instanceof Map<?, ?> outlines) || outlines.isEmpty()) {

            return "";

        }

        StringBuilder builder = new StringBuilder("\n\n[Agent 协商 · 协作者 OUTLINE 提案]\n");

        outlines.forEach((role, outline) -> {

            if (role != null && !consumerRole.equals(String.valueOf(role))) {

                builder.append("- ").append(role).append(": ")

                        .append(String.valueOf(outline).replace('\n', ' '))

                        .append('\n');

            }

        });

        builder.append("请基于上述提案避免内容重复，并补齐与其他资源的衔接。\n");

        return builder.toString();

    }



    public static String peerContextBlock(SharedBlackboard blackboard, String consumerRole) {

        Object raw = blackboard.get(PEER_SUMMARIES_KEY);

        if (!(raw instanceof Map<?, ?> summaries) || summaries.isEmpty()) {

            return "";

        }

        StringBuilder builder = new StringBuilder("\n\n[协作黑板 · 其他 Agent 已产出摘要]\n");

        summaries.forEach((role, summary) -> {

            if (role != null && !consumerRole.equals(String.valueOf(role))) {

                builder.append("- ").append(role).append(": ")

                        .append(String.valueOf(summary).replace('\n', ' '))

                        .append('\n');

            }

        });

        builder.append("请基于上述协作者摘要，避免重复并补齐衔接。\n");

        return builder.toString();

    }



    public static List<String> consumedPeerRoles(SharedBlackboard blackboard, String consumerRole) {

        Object raw = blackboard.get(PEER_SUMMARIES_KEY);

        if (!(raw instanceof Map<?, ?> summaries)) {

            raw = blackboard.get(PEER_OUTLINES_KEY);

        }

        if (!(raw instanceof Map<?, ?> peerMap)) {

            return List.of();

        }

        List<String> roles = new ArrayList<>();

        peerMap.keySet().forEach(role -> {

            if (role != null && !consumerRole.equals(String.valueOf(role))) {

                roles.add(String.valueOf(role));

            }

        });

        return roles;

    }



    private static String summarize(String content) {

        if (content == null || content.isBlank()) {

            return "（无内容）";

        }

        String compact = content.replaceAll("\\s+", " ").trim();

        return compact.length() <= 320 ? compact : compact.substring(0, 320) + "...";

    }

}


