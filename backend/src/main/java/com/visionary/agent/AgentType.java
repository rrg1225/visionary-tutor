package com.visionary.agent;

import org.springframework.stereotype.Component;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 用于标识 AgentService 实现类所处理的任务类型。
 * 与 @Component 一起使用，实现自动路由注册。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Component
public @interface AgentType {

    /**
     * 该 Agent 服务处理的任务类型（支持多枚举注册）。
     */
    AgentTaskType[] value();
}
