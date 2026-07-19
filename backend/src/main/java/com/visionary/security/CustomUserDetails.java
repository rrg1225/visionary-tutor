package com.visionary.security;

import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;

/**
 * Custom User Details 实现
 * 
 * 扩展 Spring Security 的标准 UserDetails，增加以下字段：
 * - userId: 数据库用户ID（游客为 null）
 * - email: 用户邮箱
 * - displayName: 显示名称
 * - isGuest: 是否为游客
 * 
 * 这些字段可用于在 Controller 中获取当前登录用户的详细信息。
 */
@Getter
public class CustomUserDetails implements UserDetails {

    private final Long userId;
    private final String username;
    private final String password;
    private final String email;
    private final String displayName;
    private final Collection<? extends GrantedAuthority> authorities;
    private final boolean enabled;
    private final boolean accountNonExpired;
    private final boolean accountNonLocked;
    private final boolean credentialsNonExpired;
    private final boolean isGuest;

    public CustomUserDetails(
            Long userId,
            String username,
            String password,
            String email,
            String displayName,
            Collection<? extends GrantedAuthority> authorities,
            boolean enabled,
            boolean accountNonExpired,
            boolean accountNonLocked,
            boolean credentialsNonExpired) {
        this.userId = userId;
        this.username = username;
        this.password = password;
        this.email = email;
        this.displayName = displayName;
        this.authorities = authorities;
        this.enabled = enabled;
        this.accountNonExpired = accountNonExpired;
        this.accountNonLocked = accountNonLocked;
        this.credentialsNonExpired = credentialsNonExpired;
        this.isGuest = (userId == null);  // 游客没有数据库ID
    }

    /**
     * 判断是否为游客用户
     */
    public boolean isGuest() {
        return isGuest;
    }

    /**
     * 判断是否为正式用户（非游客）
     */
    public boolean isRegisteredUser() {
        return !isGuest && userId != null;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isAccountNonExpired() {
        return accountNonExpired;
    }

    @Override
    public boolean isAccountNonLocked() {
        return accountNonLocked;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return credentialsNonExpired;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 获取用户ID（游客返回 null）
     */
    public Long getUserId() {
        return userId;
    }

    @Override
    public String toString() {
        return "CustomUserDetails{" +
                "userId=" + userId +
                ", username='" + username + '\'' +
                ", email='" + email + '\'' +
                ", displayName='" + displayName + '\'' +
                ", isGuest=" + isGuest +
                ", authorities=" + authorities +
                '}';
    }
}
