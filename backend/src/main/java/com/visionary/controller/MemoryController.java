package com.visionary.controller;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.visionary.dto.LearningPathStepDto;
import com.visionary.dto.MemoryUpdateLogDto;
import com.visionary.dto.UserMemoryDto;
import com.visionary.security.AuthContext;
import com.visionary.service.LearningPathStepService;
import com.visionary.service.UserMemoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/memory")
@RequiredArgsConstructor
public class MemoryController {

    private final UserMemoryService userMemoryService;

    @GetMapping
    public List<UserMemoryDto> listMemories() {
        Long userId = requireUserId();
        return userMemoryService.listActiveMemories(userId);
    }

    @GetMapping("/pending")
    public List<UserMemoryDto> listPending() {
        Long userId = requireUserId();
        return userMemoryService.listPendingReview(userId);
    }

    @GetMapping("/logs")
    public List<MemoryUpdateLogDto> listLogs() {
        Long userId = requireUserId();
        return userMemoryService.listUpdateLogs(userId);
    }

    @PutMapping("/manual")
    public UserMemoryDto upsertManual(@RequestBody ManualMemoryRequest request) {
        Long userId = requireUserId();
        return userMemoryService.upsertManualMemory(
                userId,
                request.memoryType(),
                request.memoryKey(),
                request.memoryValue()
        );
    }

    @PostMapping("/{memoryId}/approve")
    public UserMemoryDto approve(@PathVariable Long memoryId) {
        Long userId = requireUserId();
        return userMemoryService.approveMemory(userId, memoryId);
    }

    @PostMapping("/{memoryId}/reject")
    public UserMemoryDto reject(@PathVariable Long memoryId) {
        Long userId = requireUserId();
        return userMemoryService.rejectMemory(userId, memoryId);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ManualMemoryRequest(
            @JsonAlias({"type", "memory_type"}) String memoryType,
            @JsonAlias({"key", "memory_key"}) String memoryKey,
            @JsonAlias({"value", "memory_value", "content"}) String memoryValue
    ) {
    }

    private static Long requireUserId() {
        return AuthContext.currentRegisteredUserId()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "需要登录后访问记忆管理"));
    }
}
