package com.visionary.controller;

import com.visionary.service.ContextualTutorService;
import com.visionary.service.MultiAgentResourceService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TutoringControllerMultipartTest {

    @Test
    void acceptsBrowserMultipartTextFieldsWithoutRequiringJsonParts() throws Exception {
        ContextualTutorService tutorService = mock(ContextualTutorService.class);
        when(tutorService.answer(
                isNull(), any(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), isNull(), isNull()
        )).thenReturn(new ContextualTutorService.ContextualTutorResponse(
                "hint", true, false, "TUTORING", List.of()
        ));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new TutoringController(
                mock(MultiAgentResourceService.class), tutorService
        )).build();

        mvc.perform(multipart("/api/tutoring/ask")
                        .param("question", "请给提示")
                        .param("context", "当前题干")
                        .param("learnerProfile", "初学者")
                        .param("learningSessionId", "12")
                        .param("contextType", "SYSTEM_KNOWLEDGE")
                        .param("contextKey", "linear-algebra-for-ai")
                        .param("contextTitle", "线性代数")
                        .param("answerMode", "HINT_ONLY"))
                .andExpect(status().isOk());

        verify(tutorService).answer(
                null, 12L, "SYSTEM_KNOWLEDGE", "linear-algebra-for-ai", "线性代数",
                "HINT_ONLY", "请给提示", "当前题干", "初学者", null, null
        );
    }
}
