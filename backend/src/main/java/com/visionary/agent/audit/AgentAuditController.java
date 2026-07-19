package com.visionary.agent.audit;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agent/audit")
@RequiredArgsConstructor
public class AgentAuditController {

    private final AgentAuditService auditService;

    @GetMapping("/trace")
    public AgentTraceDto trace(
            @RequestParam(required = false) String runId,
            @RequestParam(required = false) Long learningSessionId
    ) {
        if (runId != null && !runId.isBlank()) {
            return auditService.traceByRunId(runId);
        }
        return auditService.latestTraceBySessionId(learningSessionId);
    }
}
