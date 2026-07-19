package com.visionary.controller;

import com.visionary.knowledge.SystemKnowledgeCatalogService;
import com.visionary.knowledge.SystemKnowledgeDtos.ContentSummary;
import com.visionary.knowledge.SystemKnowledgeDtos.ContentView;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/knowledge-content")
@RequiredArgsConstructor
public class SystemKnowledgeController {

    private final SystemKnowledgeCatalogService catalogService;

    @GetMapping
    public List<ContentSummary> list() {
        return catalogService.list();
    }

    @GetMapping("/{slug}")
    public ContentView get(@PathVariable String slug) {
        return catalogService.get(slug);
    }
}
