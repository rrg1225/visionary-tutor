package com.visionary.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.visionary.agent.AgentJsonParser;
import com.visionary.agent.AgentResponse;
import com.visionary.client.DeepSeekApiClient;
import com.visionary.rag.RagRetrievalResult;
import com.visionary.rag.RagRetrievalService;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DocumentAssessmentServiceTest {

    @Test
    void extractsDocxAndReturnsTransparentFallbackWhenModelIsMissing() throws Exception {
        DeepSeekApiClient deepSeek = mock(DeepSeekApiClient.class);
        RagRetrievalService rag = mock(RagRetrievalService.class);
        when(deepSeek.isConfigured()).thenReturn(false);
        when(deepSeek.providerName()).thenReturn("DeepSeek");
        when(rag.retrieveForTask(any(), anyString())).thenReturn(RagRetrievalResult.empty());
        DocumentAssessmentService service = new DocumentAssessmentService(
                deepSeek,
                rag,
                new AgentJsonParser(new ObjectMapper())
        );

        byte[] documentBytes;
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("卷积输出尺寸计算：输入 5，卷积核 3，步幅 1。学生答案为 3。");
            document.write(output);
            documentBytes = output.toByteArray();
        }
        MockMultipartFile upload = new MockMultipartFile(
                "file", "homework.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                documentBytes
        );

        var response = service.assess(upload, "检查卷积尺寸");
        assertEquals(AgentResponse.ResponseStatus.FALLBACK, response.getStatus());
        assertTrue(response.getData().ocrText().contains("卷积输出尺寸"));
        assertEquals(0.0, response.getData().confidence());
    }
}
