package com.visionary.agent.tools;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 专家工具公共接口
 * 用于 ReActSupervisorAdapter 动态工具注册和分发
 */
public interface SpecialistTool {

    /**
     * 获取工具名称（唯一标识）
     * 例如: "generate_lecture_handout", "generate_quiz", "review_and_critique"
     *
     * @return 工具名称
     */
    String getToolName();

    /**
     * 执行工具调用
     *
     * @param args 工具参数（JSON对象）
     * @param ctx ReAct 上下文，包含 runId、topic、learnerProfile 等
     * @return 工具执行结果的 JSON 字符串
     */
    String executeTool(ObjectNode args, ReActContext ctx);

    /**
     * ReAct 上下文
     * 封装工具执行时需要的上下文信息
     */
    record ReActContext(
            String runId,
            String memoryId,
            String topic,
            String learnerProfile,
            String weakPoints,
            Long learningSessionId
    ) {
        /**
         * 从 ObjectNode 中提取字符串参数
         */
        public static String getStringParam(ObjectNode args, String field, String defaultValue) {
            return args.has(field) && !args.get(field).isNull()
                    ? args.get(field).asText()
                    : defaultValue;
        }

        /**
         * 从 ObjectNode 中提取整数参数
         */
        public static int getIntParam(ObjectNode args, String field, int defaultValue) {
            return args.has(field) && !args.get(field).isNull()
                    ? args.get(field).asInt()
                    : defaultValue;
        }

        /**
         * 从 ObjectNode 中提取长整数参数
         */
        public static Long getLongParam(ObjectNode args, String field) {
            return args.has(field) && !args.get(field).isNull()
                    ? args.get(field).asLong()
                    : null;
        }

        /**
         * 从 ObjectNode 中提取布尔参数
         */
        public static boolean getBoolParam(ObjectNode args, String field, boolean defaultValue) {
            return args.has(field) && !args.get(field).isNull()
                    ? args.get(field).asBoolean()
                    : defaultValue;
        }
    }
}
