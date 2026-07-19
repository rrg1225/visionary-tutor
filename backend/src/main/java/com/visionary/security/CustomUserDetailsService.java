package com.visionary.security;

import com.visionary.config.AdminProperties;
import com.visionary.entity.User;
import com.visionary.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Custom User Details Service - 为 Spring Security 提供用户信息
 * 
 * 支持：
 * 1. 正式用户（数据库存储的用户）加载
 * 2. 游客用户（动态创建的临时用户）加载
 * 
 * 注意：此服务专门用于 Spring Security 的 DaoAuthenticationProvider，
 * 不处理 JWT 认证中的游客验证（游客在 JwtAuthenticationFilter 中处理）。
 */
@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;
    private final AdminProperties adminProperties;

    /**
     * 根据用户名加载用户信息
     * 
     * Spring Security 登录流程调用此方法验证用户凭证。
     * 游客用户不使用此流程（游客通过 JWT 直接认证）。
     * 
     * @param username 用户名
     * @return UserDetails 对象
     * @throws UsernameNotFoundException 用户不存在时抛出
     */
    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));

        // 检查用户状态
        if (user.getStatus() != User.UserStatus.ACTIVE) {
            throw new UsernameNotFoundException("User account is not active: " + username);
        }

        return buildUserDetails(user);
    }

    /**
     * 根据用户ID加载用户信息
     * 
     * 用于 JWT 认证过程中从 Token 提取的用户ID查找用户。
     * 
     * @param userId 用户ID
     * @return UserDetails 对象
     */
    public UserDetails loadUserById(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UsernameNotFoundException("User not found with id: " + userId));

        if (user.getStatus() != User.UserStatus.ACTIVE) {
            throw new UsernameNotFoundException("User account is not active: " + userId);
        }

        return buildUserDetails(user);
    }

    private CustomUserDetails buildUserDetails(User user) {
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
        if (adminProperties.isAdmin(user.getId(), user.getUsername())) {
            authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        }
        return new CustomUserDetails(
                user.getId(),
                user.getUsername(),
                user.getPassword(),
                user.getEmail(),
                user.getDisplayName(),
                authorities,
                true, true, true, true
        );
    }

    /**
     * 创建临时游客 UserDetails
     * 
     * 用于 JwtAuthenticationFilter 中处理游客 Token。
     * 游客具有 ROLE_GUEST 权限，可以访问基础学习功能。
     * 
     * @param guestId 游客ID
     * @return 游客 UserDetails
     */
    public UserDetails createGuestUserDetails(String guestId) {
        return new CustomUserDetails(
                null,  // 游客没有数据库ID
                guestId,  // 使用 guestId 作为用户名
                "",  // 游客没有密码
                null,  // 游客没有邮箱
                "Guest",  // 显示名称
                List.of(new SimpleGrantedAuthority("ROLE_GUEST")),
                true, true, true, true
        );
    }
}
