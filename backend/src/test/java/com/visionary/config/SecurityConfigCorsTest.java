package com.visionary.config;

import com.visionary.security.JwtAuthenticationFilter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.filter.CorsFilter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class SecurityConfigCorsTest {

    @Test
    void productionOriginCanCreateGuestSessionThroughReverseProxy() throws Exception {
        CorsFilter filter = new CorsFilter(configuration(
                "https://zhiyexueye.top,http://localhost:5173"));
        MockHttpServletRequest request = guestRequest("https://zhiyexueye.top");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertEquals("https://zhiyexueye.top", response.getHeader("Access-Control-Allow-Origin"));
        assertEquals(request, chain.getRequest());
    }

    @Test
    void unknownOriginIsStillRejected() throws Exception {
        CorsFilter filter = new CorsFilter(configuration("https://zhiyexueye.top"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(
                guestRequest("https://attacker.example"),
                response,
                new MockFilterChain());

        assertEquals(403, response.getStatus());
    }

    private static org.springframework.web.cors.CorsConfigurationSource configuration(String origins) {
        SecurityConfig config = new SecurityConfig(
                mock(JwtAuthenticationFilter.class),
                mock(UserDetailsService.class),
                mock(PasswordEncoder.class));
        ReflectionTestUtils.setField(config, "corsAllowedOrigins", origins);
        return config.corsConfigurationSource();
    }

    private static MockHttpServletRequest guestRequest(String origin) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/guest");
        request.setScheme("http");
        request.setServerName("127.0.0.1");
        request.setServerPort(8080);
        request.addHeader("Origin", origin);
        return request;
    }
}
