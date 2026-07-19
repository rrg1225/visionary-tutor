package com.visionary.controller;

import com.visionary.dto.LearningPathStepDto;
import com.visionary.security.AuthContext;
import com.visionary.service.LearningPathGraphService;
import com.visionary.service.LearningPathStepService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/learning-path")
@RequiredArgsConstructor
public class LearningPathProgressController {

    private final LearningPathStepService learningPathStepService;
    private final LearningPathGraphService learningPathGraphService;

    @GetMapping("/steps")
    public List<LearningPathStepDto> listSteps(@RequestParam Long learningSessionId) {
        Long userId = requireUserId();
        return learningPathStepService.listSteps(userId, learningSessionId);
    }

    @GetMapping("/graph")
    public Map<String, Object> loadGraph(@RequestParam Long artifactId) {
        requireUserId();
        return learningPathGraphService.loadPersistedGraph(artifactId);
    }

    @PutMapping("/steps/{stepOrder}/status")
    public LearningPathStepDto updateStatus(
            @PathVariable Integer stepOrder,
            @RequestParam Long learningSessionId,
            @RequestBody StatusUpdateRequest request
    ) {
        Long userId = requireUserId();
        return learningPathStepService.updateStepStatus(userId, learningSessionId, stepOrder, request.status());
    }

    public record StatusUpdateRequest(String status) {
    }

    private static Long requireUserId() {
        return AuthContext.currentRegisteredUserId()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "需要登录后访问学习路径"));
    }
}
