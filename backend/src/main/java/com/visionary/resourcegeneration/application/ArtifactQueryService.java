package com.visionary.resourcegeneration.application;

import com.visionary.dto.ResourceCard;
import com.visionary.entity.GeneratedArtifact;
import com.visionary.service.PersistenceManager;
import com.visionary.service.ResourceCardMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ArtifactQueryService {

    private final PersistenceManager persistenceManager;
    private final ResourceCardMapper resourceCardMapper;

    public ArtifactQueryService(PersistenceManager persistenceManager, ResourceCardMapper resourceCardMapper) {
        this.persistenceManager = persistenceManager;
        this.resourceCardMapper = resourceCardMapper;
    }

    @Transactional(readOnly = true)
    public List<GeneratedArtifact> listArtifacts(Long learningSessionId) {
        return persistenceManager.listVisibleArtifacts(learningSessionId);
    }

    @Transactional(readOnly = true)
    public List<ResourceCard> listResourceCards(Long learningSessionId) {
        return resourceCardMapper.toCards(persistenceManager.listVisibleArtifacts(learningSessionId));
    }
}
