package com.visionary.controller;

import com.visionary.dto.ProfileExtractionRequest;
import com.visionary.security.CustomUserDetails;
import com.visionary.service.LearnerProfileExtractionService;
import com.visionary.service.OnboardingAnswerValidationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ProfileExtractionControllerTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void registeredProfileExtractionAlwaysUsesAuthenticatedAccountId() {
        LearnerProfileExtractionService service = mock(LearnerProfileExtractionService.class);
        ProfileExtractionController controller = new ProfileExtractionController(
                service,
                mock(OnboardingAnswerValidationService.class)
        );
        authenticate(details(7L, "learner"));

        controller.extract(request(99L));

        ArgumentCaptor<ProfileExtractionRequest> captor = ArgumentCaptor.forClass(ProfileExtractionRequest.class);
        verify(service).extract(captor.capture());
        assertEquals(7L, captor.getValue().userId());
    }

    @Test
    void guestCannotWriteProfileDataIntoARegisteredAccount() {
        LearnerProfileExtractionService service = mock(LearnerProfileExtractionService.class);
        ProfileExtractionController controller = new ProfileExtractionController(
                service,
                mock(OnboardingAnswerValidationService.class)
        );
        authenticate(details(null, "gst_test"));

        controller.extract(request(99L));

        ArgumentCaptor<ProfileExtractionRequest> captor = ArgumentCaptor.forClass(ProfileExtractionRequest.class);
        verify(service).extract(captor.capture());
        assertNull(captor.getValue().userId());
    }

    private static ProfileExtractionRequest request(Long claimedUserId) {
        return new ProfileExtractionRequest(
                claimedUserId,
                "conversation",
                "assessment",
                "{}",
                "focused",
                "FULL"
        );
    }

    private static void authenticate(CustomUserDetails details) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities())
        );
    }

    private static CustomUserDetails details(Long id, String username) {
        return new CustomUserDetails(
                id,
                username,
                "password",
                username + "@example.com",
                username,
                List.of(new SimpleGrantedAuthority(id == null ? "ROLE_GUEST" : "ROLE_USER")),
                true,
                true,
                true,
                true
        );
    }
}
