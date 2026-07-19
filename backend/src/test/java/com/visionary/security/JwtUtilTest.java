package com.visionary.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtUtilTest {

    private final JwtUtil jwtUtil = new JwtUtil();

    @BeforeEach
    void configure() {
        ReflectionTestUtils.setField(jwtUtil, "secret",
                "test-secret-key-that-is-at-least-thirty-two-bytes-long-123456");
        ReflectionTestUtils.setField(jwtUtil, "expirationMs", 60_000L);
        ReflectionTestUtils.setField(jwtUtil, "guestExpirationMs", 120_000L);
    }

    @Test
    void generatesAndValidatesRegisteredAndGuestTokens() {
        String registered = jwtUtil.generateToken(42L);
        String guest = jwtUtil.generateGuestToken("gst_demo");

        assertTrue(jwtUtil.validateToken(registered));
        assertTrue(jwtUtil.validateToken(registered, "42"));
        assertEquals("42", jwtUtil.getSubjectFromToken(registered));
        assertEquals("USER", jwtUtil.getTokenType(registered));
        assertFalse(jwtUtil.isGuestToken(registered));
        assertTrue(jwtUtil.isGuestToken(guest));
        assertEquals("GUEST", jwtUtil.getTokenType(guest));
        assertEquals("gst_demo", jwtUtil.getSubjectFromToken(guest));
    }

    @Test
    void rejectsMalformedTokensAndHandlesHeaderExtraction() {
        assertFalse(jwtUtil.validateToken("invalid"));
        assertFalse(jwtUtil.validateToken("invalid", "42"));
        assertFalse(jwtUtil.isGuestToken("invalid"));
        assertEquals("UNKNOWN", jwtUtil.getTokenType("invalid"));
        assertTrue(jwtUtil.isTokenAboutToExpire("invalid"));
        assertEquals("abc", jwtUtil.extractTokenFromHeader("Bearer abc"));
        assertNull(jwtUtil.extractTokenFromHeader("Basic abc"));
        assertNull(jwtUtil.extractTokenFromHeader(null));
    }

    @Test
    void refreshPreservesIdentityAndTokenKind() {
        String original = jwtUtil.generateGuestToken("gst_refresh");
        String refreshed = jwtUtil.refreshToken(original);
        assertTrue(jwtUtil.validateToken(refreshed));
        assertEquals("gst_refresh", jwtUtil.getSubjectFromToken(refreshed));
        assertTrue(jwtUtil.isGuestToken(refreshed));
        assertFalse(jwtUtil.isTokenAboutToExpire(refreshed, 1_000L));
    }
}
