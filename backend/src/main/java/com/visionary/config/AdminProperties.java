package com.visionary.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

@Data
@Configuration
@ConfigurationProperties(prefix = "visionary.admin")
public class AdminProperties {

    /** 拥有审核权限的用户 ID 列表（逗号分隔配置或 YAML 列表） */
    private List<Long> userIds = new ArrayList<>();

    /** 稳定的管理员用户名列表，避免演示/迁移数据库中的自增 ID 漂移。 */
    private List<String> usernames = new ArrayList<>();

    public boolean isAdmin(Long userId) {
        return userId != null && userIds.contains(userId);
    }

    public boolean isAdmin(Long userId, String username) {
        return isAdmin(userId) || (username != null && usernames.stream()
                .anyMatch(candidate -> candidate != null && candidate.equalsIgnoreCase(username)));
    }
}
