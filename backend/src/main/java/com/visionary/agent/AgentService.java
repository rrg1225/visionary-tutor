package com.visionary.agent;

import com.visionary.dto.AgentInvokeRequest;
import org.springframework.web.multipart.MultipartFile;

/**
 * 策略模式核心接口：所有 AI Agent 服务实现此接口。
 * 通过 @AgentType 注解标识处理的任务类型。
 *
 * <p>多轮对话的上下文裁剪与 Token 预算请使用 {@link ConversationContextService}，
 * 由 {@link com.visionary.controller.AiStreamController} 在 SSE 流式路径中调用。</p>
 */
public interface AgentService {

    /**
     * 执行 Agent 任务处理（标准 DTO 方式）。
     *
     * @param request 调用请求
     * @return Agent 响应结果
     */
    AgentResponse<?> process(AgentInvokeRequest request);

    /**
     * 执行 Agent 任务处理（支持单独传入 MultipartFile）。
     * <p>适用于需要处理文件上传的场景，MultipartFile 与 DTO 分离，避免序列化问题。</p>
     *
     * @param request 调用请求（不包含文件）
     * @param file    上传的文件（可为 null）
     * @return Agent 响应结果
     */
    default AgentResponse<?> process(AgentInvokeRequest request, MultipartFile file) {
        // 默认实现：忽略文件，调用标准 process 方法
        // 子类可以重写此方法来处理文件
        return process(request);
    }
}
