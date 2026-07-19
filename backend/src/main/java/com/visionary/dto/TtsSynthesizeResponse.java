package com.visionary.dto;

public record TtsSynthesizeResponse(
        String audioUrl,
        boolean cacheHit,
        String provider,
        long durationMs
) {
}
