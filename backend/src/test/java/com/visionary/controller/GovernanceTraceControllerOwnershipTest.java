package com.visionary.controller;

import com.visionary.entity.GeneratedArtifact;
import com.visionary.repository.GeneratedArtifactRepository;
import com.visionary.repository.LearningSessionRepository;
import com.visionary.security.CustomUserDetails;
import com.visionary.service.GovernanceTraceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GovernanceTraceControllerOwnershipTest {

    @Mock GovernanceTraceService governanceTraceService;
    @Mock GeneratedArtifactRepository artifactRepository;
    @Mock LearningSessionRepository learningSessionRepository;

    @InjectMocks GovernanceTraceController controller;

    @BeforeEach
    void authenticateUser() {
        CustomUserDetails details = new CustomUserDetails(
                7L, "learner", "unused", "learner@example.test", "Learner",
                List.of(new SimpleGrantedAuthority("ROLE_USER")), true, true, true, true
        );
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities())
        );
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void hidesAnotherUsersGovernanceTrace() {
        GeneratedArtifact artifact = new GeneratedArtifact();
        artifact.setLearningSessionId(55L);
        when(artifactRepository.findById(9L)).thenReturn(Optional.of(artifact));
        when(learningSessionRepository.existsByIdAndUserId(55L, 7L)).thenReturn(false);

        assertThatThrownBy(() -> controller.getGovernanceTrace(9L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404 NOT_FOUND");
        verifyNoInteractions(governanceTraceService);
    }
}
