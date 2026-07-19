package com.visionary.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.visionary.config.PptxProperties;
import com.visionary.dto.PptxEditExportRequest;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PptxExportServiceTest {

    @Test
    void javaFallbackAlwaysProducesAReadableDeck() throws Exception {
        PptxExportService service = new PptxExportService(
                null, null, null, new ObjectMapper(), new PptxProperties()
        );

        byte[] bytes = service.generateJavaFallback(
                "卷积神经网络",
                List.of("卷积核提取局部特征", "步幅决定采样间隔", "填充控制边界尺寸"),
                "测试导出"
        );

        assertTrue(bytes.length > 1000);
        try (XMLSlideShow deck = new XMLSlideShow(new ByteArrayInputStream(bytes))) {
            assertEquals(2, deck.getSlides().size());
        }
    }

    @Test
    void editedExportKeepsUserSlideTitlesAndOrder() throws Exception {
        PptxExportService service = new PptxExportService(
                null, null, null, new ObjectMapper(), new PptxProperties()
        );
        byte[] bytes = service.exportEditedPptx(new PptxEditExportRequest(
                "CNN 学习包",
                "编辑后导出",
                List.of(
                        new PptxEditExportRequest.SlideEdit("第一步：卷积核", "观察局部连接与权值共享。"),
                        new PptxEditExportRequest.SlideEdit("第二步：步幅", "- 调整 stride\n- 比较输出尺寸")
                )
        ));

        try (XMLSlideShow deck = new XMLSlideShow(new ByteArrayInputStream(bytes))) {
            assertEquals(3, deck.getSlides().size());
            assertTrue(deck.getSlides().get(1).getShapes().stream()
                    .anyMatch(shape -> shape instanceof org.apache.poi.xslf.usermodel.XSLFTextShape text
                            && text.getText().contains("第一步：卷积核")));
            assertTrue(deck.getSlides().get(2).getShapes().stream()
                    .anyMatch(shape -> shape instanceof org.apache.poi.xslf.usermodel.XSLFTextShape text
                            && text.getText().contains("第二步：步幅")));
        }
    }
}
