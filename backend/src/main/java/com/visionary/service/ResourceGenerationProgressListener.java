package com.visionary.service;

import com.visionary.dto.ResourceGenerationProgressEvent;

@FunctionalInterface
public interface ResourceGenerationProgressListener {

    void onProgress(ResourceGenerationProgressEvent event);
}
