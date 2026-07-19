package com.visionary.service;

import com.visionary.entity.LearningSession;
import com.visionary.entity.SessionChatMessage;
import com.visionary.repository.LearningSessionRepository;
import com.visionary.repository.SessionChatMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatHistoryScopeServiceTest {

    @Mock
    private SessionChatMessageRepository messageRepository;
    @Mock
    private LearningSessionRepository learningSessionRepository;

    private ChatHistoryService service;

    @BeforeEach
    void setUp() {
        service = new ChatHistoryService(messageRepository, learningSessionRepository);
        LearningSession session = new LearningSession();
        session.setId(9L);
        session.setUserId(7L);
        when(learningSessionRepository.findById(9L)).thenReturn(Optional.of(session));
    }

    @Test
    void listsOnlyMessagesForRequestedLearningContext() {
        SessionChatMessage message = message("assistant", "继续检查 stride。", "FIXED_EXAM", "paper:q2", 4);
        when(messageRepository.findByLearningSessionIdAndContextTypeAndContextKeyOrderBySeqAsc(
                9L,
                "FIXED_EXAM",
                "paper:q2"
        )).thenReturn(List.of(message));

        var result = service.listMessages(9L, 7L, "fixed-exam", "paper:q2");

        assertEquals(1, result.size());
        assertEquals("FIXED_EXAM", result.get(0).contextType());
        assertEquals("paper:q2", result.get(0).contextKey());
    }

    @Test
    void persistsScopeMetadataWithoutMixingItIntoGeneralConversation() {
        when(messageRepository.countByLearningSessionId(9L)).thenReturn(3L);
        when(messageRepository.findTopByLearningSessionIdAndContextTypeAndContextKeyOrderBySeqDesc(
                9L,
                "TEXTBOOK",
                "book:cnn:chapter:2"
        )).thenReturn(Optional.empty());
        when(messageRepository.save(any(SessionChatMessage.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.appendMessage(
                9L,
                7L,
                "user",
                "这个推导为什么要向下取整？",
                "textbook",
                "book:cnn:chapter:2",
                "CNN 教材 · 输出尺寸"
        );

        ArgumentCaptor<SessionChatMessage> captor = ArgumentCaptor.forClass(SessionChatMessage.class);
        verify(messageRepository).save(captor.capture());
        SessionChatMessage saved = captor.getValue();
        assertEquals("TEXTBOOK", saved.getContextType());
        assertEquals("book:cnn:chapter:2", saved.getContextKey());
        assertEquals("CNN 教材 · 输出尺寸", saved.getContextTitle());
        assertEquals(4, saved.getSeq());
    }

    private SessionChatMessage message(String role, String content, String type, String key, int seq) {
        SessionChatMessage value = new SessionChatMessage();
        value.setLearningSessionId(9L);
        value.setUserId(7L);
        value.setRole(role);
        value.setContent(content);
        value.setContextType(type);
        value.setContextKey(key);
        value.setSeq(seq);
        return value;
    }
}
