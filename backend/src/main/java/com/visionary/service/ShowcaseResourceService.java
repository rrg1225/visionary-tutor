package com.visionary.service;

import com.visionary.dto.ResourceCard;
import com.visionary.entity.GeneratedArtifact;
import com.visionary.repository.GeneratedArtifactRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ShowcaseResourceService {

    private final ShowcaseContentSeedService showcaseContentSeedService;
    private final GeneratedArtifactRepository artifactRepository;
    private final ResourceCardMapper resourceCardMapper;

    @Transactional(readOnly = true)
    public List<ResourceCard> listShowcaseCards() {
        Optional<Long> sessionId = showcaseContentSeedService.findShowcaseSessionId();
        if (sessionId.isEmpty()) {
            return List.of();
        }
        List<GeneratedArtifact> artifacts =
                artifactRepository.findByLearningSessionIdOrderByGmtCreatedDesc(sessionId.get());
        return artifacts.stream()
                .map(resourceCardMapper::toCard)
                .map(card -> card.toBuilder()
                        .showcase(true)
                        .sessionTopic(ShowcaseContentSeedService.SHOWCASE_SESSION_TOPIC)
                        .build())
                .toList();
    }
}
