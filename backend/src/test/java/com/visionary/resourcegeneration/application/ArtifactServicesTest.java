package com.visionary.resourcegeneration.application;

import com.visionary.dto.ResourceCard;
import com.visionary.entity.GeneratedArtifact;
import com.visionary.service.PersistenceManager;
import com.visionary.service.ResourceCardMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ArtifactServicesTest {

    private final PersistenceManager persistenceManager = mock(PersistenceManager.class);
    private final ResourceCardMapper resourceCardMapper = mock(ResourceCardMapper.class);

    @Test
    void queryServiceUsesTheSharedPersistenceVisibilityPolicy() {
        GeneratedArtifact artifact = new GeneratedArtifact();
        ResourceCard card = mock(ResourceCard.class);
        when(persistenceManager.listVisibleArtifacts(3L)).thenReturn(List.of(artifact));
        when(resourceCardMapper.toCards(List.of(artifact))).thenReturn(List.of(card));
        ArtifactQueryService service = new ArtifactQueryService(persistenceManager, resourceCardMapper);

        assertEquals(List.of(artifact), service.listArtifacts(3L));
        assertEquals(List.of(card), service.listResourceCards(3L));
    }
}
