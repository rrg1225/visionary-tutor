package com.visionary.rag;

import com.visionary.client.QwenVlApiClient;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

@Slf4j
@Service
@RequiredArgsConstructor
public class ImageCaptionIngestionService {

    private final QwenVlApiClient qwenVlApiClient;
    private final VectorDbService vectorDbService;

    private static final String MEDIA_DIR = "knowledge_base/processed/source_layer/media";
    private static final List<String> SUPPORTED_EXT = Arrays.asList(".png", ".jpg", ".jpeg");

    /**
     * 扫描媒体目录，对所有图像生成详细教学描述并摄取到向量库。
     */
    public void ingestAllImages() {
        Path mediaPath = Paths.get(MEDIA_DIR).toAbsolutePath().normalize();
        if (!Files.isDirectory(mediaPath)) {
            log.error("媒体目录不存在: {}", mediaPath);
            return;
        }

        try (Stream<Path> paths = Files.walk(mediaPath)) {
            List<Path> imageFiles = paths
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase();
                        return SUPPORTED_EXT.stream().anyMatch(name::endsWith);
                    })
                    .sorted()
                    .toList();

            int total = imageFiles.size();
            log.info("发现 {} 个教学图像，开始 Caption 摄取...", total);

            int processed = 0;
            for (Path imagePath : imageFiles) {
                try {
                    File imageFile = imagePath.toFile();
                    String relativePath = "/media/" + imagePath.getFileName().toString();

                    String prompt = "作为资深计算机和数学教师，请极其详细地描述这张教学配图。如果是算法图解（如树、图结构），请详细说明节点关系、旋转过程和变化；如果是数学公式，请描述公式含义和推导逻辑；如果是代码或架构图，请说明核心逻辑。请直接输出描述，不要说废话。";

                    String caption = qwenVlApiClient.describeLocalImage(imageFile, prompt);

                    Metadata metadata = new Metadata();
                    metadata.put("image_path", relativePath);
                    metadata.put("chunk_type", "image_caption");
                    metadata.put("source", imagePath.getFileName().toString());
                    metadata.put("category", "media");
                    metadata.put("layer", "course_layer");
                    metadata.put("chroma_layer", "application_layer");

                    Document doc = Document.from(caption, metadata);
                    vectorDbService.upsert(doc);

                    processed++;
                    if (processed % 10 == 0 || processed == total) {
                        log.info("Processed {}/{} images...", processed, total);
                    }

                    Thread.sleep(1000);

                } catch (IOException e) {
                    log.warn("图像处理失败: {}, error={}", imagePath, e.getMessage());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("摄取过程被中断");
                    break;
                } catch (Exception e) {
                    log.warn("图像摄取异常: {}, error={}", imagePath, e.getMessage());
                }
            }

            log.info("图像字幕摄取完成: 共处理 {} / {} 个文件", processed, total);

        } catch (IOException e) {
            log.error("扫描媒体目录失败", e);
        }
    }
}
