package com.visionary.service;

import com.visionary.entity.User;
import com.visionary.repository.GeneratedArtifactRepository;
import com.visionary.repository.LearningEventMetricRepository;
import com.visionary.repository.LearningSessionRepository;
import com.visionary.repository.MemoryUpdateLogRepository;
import com.visionary.repository.ResourceUsageRecordRepository;
import com.visionary.repository.SessionChatMessageRepository;
import com.visionary.repository.UserMemoryRepository;
import com.visionary.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserDataExportServiceTest {

    @Test
    void newUserWithNullableProfileFieldsGetsEmptyExportInsteadOfServerError() {
        UserRepository users = mock(UserRepository.class);
        UserMemoryRepository memories = mock(UserMemoryRepository.class);
        MemoryUpdateLogRepository memoryLogs = mock(MemoryUpdateLogRepository.class);
        LearningSessionRepository sessions = mock(LearningSessionRepository.class);
        SessionChatMessageRepository chats = mock(SessionChatMessageRepository.class);
        GeneratedArtifactRepository artifacts = mock(GeneratedArtifactRepository.class);
        LearningEventMetricRepository metrics = mock(LearningEventMetricRepository.class);
        ResourceUsageRecordRepository usage = mock(ResourceUsageRecordRepository.class);
        UserDataExportService service = new UserDataExportService(
                users, memories, memoryLogs, sessions, chats, artifacts, metrics, usage
        );

        User user = new User();
        user.setId(22L);
        user.setUsername("new-user");
        user.setStatus(User.UserStatus.ACTIVE);
        when(users.findById(22L)).thenReturn(Optional.of(user));
        when(sessions.findByUserIdOrderByGmtCreatedDesc(22L)).thenReturn(List.of());
        when(memories.findByUserIdOrderByGmtModifiedDesc(22L)).thenReturn(List.of());
        when(memoryLogs.findByUserIdOrderByGmtCreatedDesc(22L)).thenReturn(List.of());
        when(metrics.findByUserIdOrderByEventTimeDesc(22L)).thenReturn(List.of());
        when(usage.findByUserIdOrderByGmtCreatedDesc(22L)).thenReturn(List.of());

        UserDataExportService.UserDataExport result = service.exportUserData(22L);

        assertEquals("new-user", result.user().get("username"));
        assertNull(result.user().get("email"));
        assertNull(result.user().get("learningGoal"));
        assertEquals(List.of(), result.sessions());
        assertEquals(List.of(), result.memories());
    }
}

