package com.visionary.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

public final class AuthContext {

    private AuthContext() {
    }

    public static Optional<Long> currentRegisteredUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return Optional.empty();
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof CustomUserDetails details && details.isRegisteredUser()) {
            return Optional.of(details.getUserId());
        }
        return Optional.empty();
    }
}
