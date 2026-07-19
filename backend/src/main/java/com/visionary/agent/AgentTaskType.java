package com.visionary.agent;

/**
 * Frontend-facing task identifiers for the AI router.
 */
public enum AgentTaskType {

    /** Fallback task marker for error responses before routing is known. */
    UNKNOWN,

    /** Emotion / sensory profile analysis (Xunfei Spark). */
    EMOTION_PROFILE,

    /** Knowledge-gap diagnosis and logical reasoning (DeepSeek-V3/R1 + RAG). */
    KNOWLEDGE_DIAGNOSIS,

    /** Personalized handout / material generation (DeepSeek + RAG). */
    RESOURCE_GENERATION,

    /** Visual assessment and draft grading (Qwen-VL-Max). */
    VISUAL_ASSESSMENT
}
