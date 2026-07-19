package com.visionary.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 密码编码器配置
 * 
 * 使用 BCrypt 算法进行密码加密，这是 Spring Security 推荐的标准做法。
 * 将 PasswordEncoder 单独配置，便于在其他服务中注入使用。
 */
@Configuration
public class PasswordEncoderConfig {

    /**
     * 创建 BCrypt 密码编码器
     * 
     * BCrypt 特点：
     * 1. 自动包含随机 salt，无需单独存储
     * 2. 可通过 strength 参数调整计算强度（默认 10）
     * 3. 结果格式：$2a$strength$encodedsalt...encodedhash
     * 
     * @return BCryptPasswordEncoder 实例
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        // 默认 strength=10，每次哈希约 100ms，可根据服务器性能调整
        return new BCryptPasswordEncoder();
    }
}
