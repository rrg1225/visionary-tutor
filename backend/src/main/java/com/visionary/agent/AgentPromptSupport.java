package com.visionary.agent;

import com.visionary.agent.core.AgentTask;

final class AgentPromptSupport {

    private AgentPromptSupport() {
    }

    static String revisionBlock(AgentTask task) {
        Object instruction = task.input().get("revisionInstruction");
        if (instruction == null || instruction.toString().isBlank()) {
            return "";
        }
        String previous = String.valueOf(task.input().getOrDefault("previousContent", ""));
        int limit = Math.min(previous.length(), 900);
        return "\n\nRevision request:\n"
                + "- Round: " + task.input().getOrDefault("revisionRound", 1) + "\n"
                + "- Critic instruction: " + instruction + "\n"
                + "- Previous draft excerpt:\n" + previous.substring(0, limit) + "\n"
                + "Revise the artifact directly. Keep valid citations, fix the criticized issues, and do not merely repeat the previous draft.\n";
    }
}
