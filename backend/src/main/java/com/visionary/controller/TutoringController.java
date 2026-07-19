package com.visionary.controller;

import com.visionary.dto.TutoringMultimodalRequest;
import com.visionary.dto.TutoringMultimodalResponse;
import com.visionary.service.MultiAgentResourceService;
import com.visionary.service.ContextualTutorService;
import com.visionary.security.AuthContext;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/tutoring")
@RequiredArgsConstructor
public class TutoringController {

    private final MultiAgentResourceService resourceService;
    private final ContextualTutorService contextualTutorService;

    /**
     * Generate mind map and/or local animation demo from current tutoring dialogue (bonus item 4).
     */
    @PostMapping("/multimodal")
    public TutoringMultimodalResponse generateMultimodal(@Valid @RequestBody TutoringMultimodalRequest request) {
        return resourceService.generateTutoringMultimodal(request);
    }

    @PostMapping(value = "/ask", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ContextualTutorService.ContextualTutorResponse ask(
            @RequestParam("question") String question,
            @RequestParam(value = "context", required = false) String context,
            @RequestParam(value = "learnerProfile", required = false) String learnerProfile,
            @RequestParam(value = "learningSessionId", required = false) Long learningSessionId,
            @RequestParam(value = "contextType", required = false) String contextType,
            @RequestParam(value = "contextKey", required = false) String contextKey,
            @RequestParam(value = "contextTitle", required = false) String contextTitle,
            @RequestParam(value = "answerMode", required = false) String answerMode,
            @RequestPart(value = "image", required = false) MultipartFile image
    ) throws Exception {
        byte[] imageBytes = null;
        String mimeType = null;
        if (image != null && !image.isEmpty()) {
            if (image.getSize() > 10 * 1024 * 1024L) {
                throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "图片不能超过 10MB");
            }
            mimeType = image.getContentType();
            if (mimeType == null || !mimeType.startsWith("image/")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "AI 老师当前只接受图片附件");
            }
            imageBytes = image.getBytes();
        }
        return contextualTutorService.answer(
                AuthContext.currentRegisteredUserId().orElse(null),
                learningSessionId,
                contextType,
                contextKey,
                contextTitle,
                answerMode,
                question,
                context,
                learnerProfile,
                imageBytes,
                mimeType
        );
    }
}
