package com.visionary.controller;

import com.visionary.dto.NotificationDto;
import com.visionary.security.CustomUserDetails;
import com.visionary.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping("/unread")
    public List<NotificationDto> unread() {
        return notificationService.listUnread(requireRegisteredUserId());
    }

    @PostMapping("/{id}/read")
    public void markRead(@PathVariable Long id) {
        notificationService.markRead(requireRegisteredUserId(), id);
    }

    private static Long requireRegisteredUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "需要登录");
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof CustomUserDetails details && details.isRegisteredUser()) {
            return details.getUserId();
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要注册账号");
    }
}
