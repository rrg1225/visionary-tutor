package com.visionary.agent.core;

import java.util.Locale;

/**
 * Standard message types for agent-to-agent communication on the MessageBus.
 */
public enum AgentMessageType {

    HANDOFF,
    PROPOSE,
    OUTLINE_PROPOSAL,
    ARTIFACT_READY,
    ALIGNMENT_REQUEST,
    CRITIQUE_REQUEST,
    CRITIQUE_RESPONSE,
    REVISION_REQUIRED,
    CONSENSUS;

    /**
     * Maps legacy string type values used before this enum was introduced.
     */
    public static AgentMessageType fromLegacy(String value) {
        if (value == null || value.isBlank()) {
            return HANDOFF;
        }
        return switch (value.toUpperCase(Locale.ROOT)) {
            case "HANDOFF" -> HANDOFF;
            case "PROPOSE" -> PROPOSE;
            case "OUTLINE_PROPOSAL", "OUTLINE" -> OUTLINE_PROPOSAL;
            case "ARTIFACT_READY", "RESULT" -> ARTIFACT_READY;
            case "ALIGNMENT_REQUEST", "ALIGN" -> ALIGNMENT_REQUEST;
            case "CRITIQUE_REQUEST" -> CRITIQUE_REQUEST;
            case "CRITIQUE_RESPONSE", "REFLECT" -> CRITIQUE_RESPONSE;
            case "REVISION_REQUIRED", "REVISION" -> REVISION_REQUIRED;
            case "CONSENSUS" -> CONSENSUS;
            default -> {
                try {
                    yield AgentMessageType.valueOf(value.toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ex) {
                    yield HANDOFF;
                }
            }
        };
    }
}
