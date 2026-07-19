package com.visionary.controller;

import com.visionary.dto.TtsSynthesizeRequest;
import com.visionary.dto.TtsSynthesizeResponse;
import com.visionary.service.TtsService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;

@RestController
@RequestMapping("/api/tts")
@RequiredArgsConstructor
public class TtsController {

    private final TtsService ttsService;

    @GetMapping("/health")
    public ResponseEntity<?> health() {
        return ResponseEntity.ok(java.util.Map.of(
                "enabled", ttsService.isEnabled(),
                "message", ttsService.healthMessage()
        ));
    }

    @PostMapping("/synthesize")
    public TtsSynthesizeResponse synthesize(@Valid @RequestBody TtsSynthesizeRequest request) {
        return ttsService.synthesize(
                request.text(),
                request.voice(),
                request.speed(),
                request.format()
        );
    }

    @GetMapping("/audio/{cacheKey}")
    public ResponseEntity<FileSystemResource> audio(@PathVariable String cacheKey) {
        Path path = ttsService.resolveAudioFile(cacheKey);
        FileSystemResource resource = new FileSystemResource(path);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + cacheKey + ".mp3\"")
                .contentType(MediaType.parseMediaType("audio/mpeg"))
                .body(resource);
    }
}
