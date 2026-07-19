package com.visionary.agent;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * Agent 服务注册表。
 * Spring Boot 启动时自动扫描所有带有 @AgentType 注解的 AgentService 实现类，
 * 并将其注入到路由表中。
 */
@Slf4j
@Component
public class AgentHandlerRegistry {

    private final ApplicationContext applicationContext;
    private final Map<AgentTaskType, AgentService> handlers = new EnumMap<>(AgentTaskType.class);

    public AgentHandlerRegistry(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    /**
     * Spring Boot 启动时自动扫描并注册所有 AgentService 实现。
     */
    @PostConstruct
    public void registerHandlers() {
        Map<String, Object> beans = applicationContext.getBeansWithAnnotation(AgentType.class);

        for (Map.Entry<String, Object> entry : beans.entrySet()) {
            Object bean = entry.getValue();
            if (bean instanceof AgentService handler) {
                AgentType annotation = AnnotationUtils.findAnnotation(AopUtils.getTargetClass(bean), AgentType.class);
                if (annotation != null) {
                    for (AgentTaskType taskType : annotation.value()) {
                        AgentService existing = handlers.put(taskType, handler);
                        if (existing != null && existing != handler) {
                            throw new IllegalStateException(
                                    "Duplicate agent registration for task type: " + taskType +
                                            ". Conflicting beans: " + existing.getClass().getName() +
                                            " and " + handler.getClass().getName()
                            );
                        }
                        log.info("Registered AgentService [{}] for task type: {}",
                                handler.getClass().getSimpleName(), taskType);
                    }
                }
            }
        }

        // 验证所有任务类型都有对应的处理器
        for (AgentTaskType taskType : AgentTaskType.values()) {
            if (!handlers.containsKey(taskType)) {
                log.warn("No AgentService registered for task type: {}", taskType);
            }
        }

        log.info("AgentHandlerRegistry initialized with {} handlers", handlers.size());
    }

    /**
     * 根据任务类型获取对应的 Agent 服务。
     *
     * @param taskType 任务类型
     * @return AgentService 实现
     * @throws IllegalArgumentException 如果找不到对应的处理器
     */
    public AgentService getRequired(AgentTaskType taskType) {
        AgentService handler = handlers.get(taskType);
        if (handler == null) {
            throw new IllegalArgumentException("Unsupported task type: " + taskType);
        }
        return handler;
    }

    /**
     * 根据任务类型获取对应的 Agent 服务（可选）。
     *
     * @param taskType 任务类型
     * @return AgentService 实现，可能为 null
     */
    public AgentService getOrNull(AgentTaskType taskType) {
        return handlers.get(taskType);
    }

    /**
     * 检查是否支持指定的任务类型。
     *
     * @param taskType 任务类型
     * @return 是否支持
     */
    public boolean supports(AgentTaskType taskType) {
        return handlers.containsKey(taskType);
    }

    /**
     * 获取已注册的所有任务类型。
     *
     * @return 任务类型集合
     */
    public Map<AgentTaskType, AgentService> getAllHandlers() {
        return new EnumMap<>(handlers);
    }
}
