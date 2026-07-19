package com.visionary.security;

import com.visionary.config.AdminProperties;
import com.visionary.entity.User;
import com.visionary.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SecurityAuthenticationTest {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void userDetailsServiceBuildsUserAdminAndGuestAuthorities() {
        UserRepository repository = mock(UserRepository.class);
        AdminProperties adminProperties = mock(AdminProperties.class);
        CustomUserDetailsService service = new CustomUserDetailsService(repository, adminProperties);
        User user = activeUser(9L, "learner");
        when(repository.findByUsername("learner")).thenReturn(Optional.of(user));
        when(repository.findById(9L)).thenReturn(Optional.of(user));
        when(adminProperties.isAdmin(9L, "learner")).thenReturn(true);

        CustomUserDetails details = (CustomUserDetails) service.loadUserByUsername("learner");
        assertTrue(details.isRegisteredUser());
        assertFalse(details.isGuest());
        assertEquals(2, details.getAuthorities().size());
        assertEquals("learner", service.loadUserById(9L).getUsername());
        CustomUserDetails guest = (CustomUserDetails) service.createGuestUserDetails("gst_1");
        assertTrue(guest.isGuest());
        assertFalse(guest.isRegisteredUser());
        assertTrue(guest.toString().contains("gst_1"));
    }

    @Test
    void userDetailsServiceRejectsMissingAndInactiveAccounts() {
        UserRepository repository = mock(UserRepository.class);
        CustomUserDetailsService service = new CustomUserDetailsService(repository, mock(AdminProperties.class));
        when(repository.findByUsername("missing")).thenReturn(Optional.empty());
        User inactive = activeUser(2L, "inactive");
        inactive.setStatus(User.UserStatus.INACTIVE);
        when(repository.findById(2L)).thenReturn(Optional.of(inactive));

        assertThrows(UsernameNotFoundException.class, () -> service.loadUserByUsername("missing"));
        assertThrows(UsernameNotFoundException.class, () -> service.loadUserById(2L));
    }

    @Test
    void authContextOnlyExposesRegisteredAuthenticatedUsers() {
        assertTrue(AuthContext.currentRegisteredUserId().isEmpty());
        CustomUserDetails registered = details(7L, "learner");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(registered, null, registered.getAuthorities()));
        assertEquals(Optional.of(7L), AuthContext.currentRegisteredUserId());

        CustomUserDetails guest = details(null, "gst_1");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(guest, null, guest.getAuthorities()));
        assertTrue(AuthContext.currentRegisteredUserId().isEmpty());
    }

    @Test
    void jwtFilterAuthenticatesGuestAndRegisteredRequests() throws Exception {
        JwtUtil jwtUtil = mock(JwtUtil.class);
        CustomUserDetailsService detailsService = mock(CustomUserDetailsService.class);
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtUtil, detailsService);
        when(jwtUtil.validateToken("guest-token")).thenReturn(true);
        when(jwtUtil.getSubjectFromToken("guest-token")).thenReturn("gst_1");
        when(jwtUtil.isGuestToken("guest-token")).thenReturn(true);
        when(detailsService.createGuestUserDetails("gst_1")).thenReturn(details(null, "gst_1"));

        filter.doFilterInternal(request("Bearer guest-token"), new MockHttpServletResponse(), new MockFilterChain());
        assertEquals("gst_1", SecurityContextHolder.getContext().getAuthentication().getName());
        SecurityContextHolder.clearContext();

        when(jwtUtil.validateToken("user-token")).thenReturn(true);
        when(jwtUtil.getSubjectFromToken("user-token")).thenReturn("8");
        when(jwtUtil.isGuestToken("user-token")).thenReturn(false);
        when(detailsService.loadUserById(8L)).thenReturn(details(8L, "learner"));
        filter.doFilterInternal(request("Bearer user-token"), new MockHttpServletResponse(), new MockFilterChain());
        assertEquals("learner", SecurityContextHolder.getContext().getAuthentication().getName());
        verify(detailsService).loadUserById(8L);
    }

    @Test
    void jwtFilterPassesThroughMissingInvalidAndMalformedTokens() throws Exception {
        JwtUtil jwtUtil = mock(JwtUtil.class);
        CustomUserDetailsService detailsService = mock(CustomUserDetailsService.class);
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtUtil, detailsService);
        filter.doFilterInternal(request(null), new MockHttpServletResponse(), new MockFilterChain());
        when(jwtUtil.validateToken("invalid")).thenReturn(false);
        filter.doFilterInternal(request("Bearer invalid"), new MockHttpServletResponse(), new MockFilterChain());
        when(jwtUtil.validateToken("bad-subject")).thenReturn(true);
        when(jwtUtil.getSubjectFromToken("bad-subject")).thenReturn("not-a-number");
        when(jwtUtil.isGuestToken("bad-subject")).thenReturn(false);
        filter.doFilterInternal(request("Bearer bad-subject"), new MockHttpServletResponse(), new MockFilterChain());
        assertTrue(SecurityContextHolder.getContext().getAuthentication() == null);

        MockHttpServletRequest auth = new MockHttpServletRequest("GET", "/api/auth/login");
        MockHttpServletRequest quota = new MockHttpServletRequest("GET", "/api/auth/guest/quota");
        MockHttpServletRequest context = new MockHttpServletRequest("PUT", "/api/auth/guest/context");
        assertTrue(filter.shouldNotFilter(auth));
        assertFalse(filter.shouldNotFilter(quota));
        assertFalse(filter.shouldNotFilter(context));
    }

    private static MockHttpServletRequest request(String authorization) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/resources");
        if (authorization != null) request.addHeader("Authorization", authorization);
        return request;
    }

    private static CustomUserDetails details(Long id, String username) {
        return new CustomUserDetails(
                id, username, "password", "mail@example.com", username,
                List.of(new SimpleGrantedAuthority(id == null ? "ROLE_GUEST" : "ROLE_USER")),
                true, true, true, true
        );
    }

    private static User activeUser(Long id, String username) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setPassword("password");
        user.setEmail(username + "@example.com");
        user.setDisplayName(username);
        user.setStatus(User.UserStatus.ACTIVE);
        return user;
    }
}
