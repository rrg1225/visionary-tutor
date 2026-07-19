package com.visionary.repository;

import com.visionary.entity.SessionChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SessionChatMessageRepository extends JpaRepository<SessionChatMessage, Long> {

    List<SessionChatMessage> findByLearningSessionIdOrderBySeqAsc(Long learningSessionId);

    List<SessionChatMessage> findByLearningSessionIdAndContextTypeAndContextKeyOrderBySeqAsc(
            Long learningSessionId,
            String contextType,
            String contextKey
    );

    Optional<SessionChatMessage> findTopByLearningSessionIdOrderBySeqDesc(Long learningSessionId);

    Optional<SessionChatMessage> findTopByLearningSessionIdAndContextTypeAndContextKeyOrderBySeqDesc(
            Long learningSessionId,
            String contextType,
            String contextKey
    );

    long countByLearningSessionId(Long learningSessionId);

    void deleteByLearningSessionId(Long learningSessionId);
}
