package com.visionary.os;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.visionary.entity.LearningOsEvent;
import com.visionary.entity.User;
import com.visionary.repository.LearningOsEventRepository;
import com.visionary.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class LearningEventBus {

    private final ApplicationEventPublisher publisher;
    private final LearningOsEventRepository eventRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public void publish(LearningEvent event) {
        LearningOsEvent row = new LearningOsEvent();
        row.setUserId(event.userId());
        row.setLearningSessionId(event.learningSessionId());
        row.setEventType(event.type().name());
        row.setPolicyReason(null);
        try {
            row.setPayloadJson(objectMapper.writeValueAsString(event.payload()));
        } catch (Exception e) {
            row.setPayloadJson("{}");
        }
        eventRepository.save(row);
        publisher.publishEvent(event);
        log.info("[LearningOS] event {} user={} session={}", event.type(), event.userId(), event.learningSessionId());
    }

    @Transactional
    public void attachPolicyReason(Long eventId, String reason) {
        if (eventId == null || reason == null || reason.isBlank()) {
            return;
        }
        eventRepository.findById(eventId).ifPresent(row -> {
            row.setPolicyReason(reason);
            eventRepository.save(row);
        });
    }

    public List<LearningOsEvent> recentEvents(Long userId) {
        if (userId == null) {
            return List.of();
        }
        return eventRepository.findTop20ByUserIdOrderByGmtCreatedDesc(userId);
    }
}
