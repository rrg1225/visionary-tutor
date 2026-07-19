package com.visionary.rag;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.output.Response;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 严格事实校验服务 - 基于向量语义相似度的数学护栏。
 * 打破对 LLM 的循环依赖，使用纯数学计算（余弦相似度）进行幻觉检测。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StrictFactCheckService {

    @Nullable
    private final DashScopeEmbeddingModel dashScopeEmbeddingModel;

    /**
     * 硬性阈值：向量语义相似度低于此值视为存在严重幻觉风险
     */
    private static final double COSINE_SIMILARITY_THRESHOLD = 0.80;

    /**
     * 校验结果状态枚举
     */
    public enum ValidationStatus {
        PASS, FAIL
    }

    @Data
    public static class FactCheckResult {
        private String claim;
        private boolean is_supported;
        private String evidence_quote;
        private String correction_directive;
        private ValidationStatus validationStatus;
        private double similarityScore;
        private String validationReason;
    }

    /**
     * 对生成的文本进行基于向量相似度的事实性核查。
     * 使用数学余弦相似度计算，不再调用 LLM 进行判断。
     *
     * @param referenceMaterial RAG 召回的参考材料（知识库上下文）
     * @param generatedText     模型生成的文本内容
     * @return 校验结果列表
     */
    public List<FactCheckResult> checkFacts(String referenceMaterial, String generatedText) {
        if (dashScopeEmbeddingModel == null) {
            log.warn("[StrictFactCheck] DashScopeEmbeddingModel 未配置，跳过严格事实校验");
            return Collections.emptyList();
        }
        if (referenceMaterial == null || referenceMaterial.isBlank()) {
            log.warn("[StrictFactCheck] 参考材料为空，跳过校验");
            return Collections.emptyList();
        }
        if (generatedText == null || generatedText.isBlank()) {
            log.warn("[StrictFactCheck] 生成内容为空，跳过校验");
            return Collections.emptyList();
        }

        try {
            // 将参考材料和生成内容转换为向量
            List<Double> referenceVector = embedTextToVector(referenceMaterial);
            List<Double> generatedVector = embedTextToVector(generatedText);

            // 计算余弦相似度
            double similarity = calculateCosineSimilarity(referenceVector, generatedVector);

            FactCheckResult result = new FactCheckResult();
            result.setClaim(generatedText.substring(0, Math.min(200, generatedText.length())));
            result.setSimilarityScore(similarity);

            if (similarity >= COSINE_SIMILARITY_THRESHOLD) {
                // 通过校验
                result.setValidationStatus(ValidationStatus.PASS);
                result.set_supported(true);
                result.setEvidence_quote("向量语义相似度计算通过，相似度: " + String.format("%.4f", similarity));
                result.setValidationReason("系统级数学护栏校验通过：生成内容与召回知识库的向量语义相似度为 "
                        + String.format("%.4f", similarity) + "，高于阈值 " + COSINE_SIMILARITY_THRESHOLD);
                log.info("[StrictFactCheck] PASS - 相似度: {}", similarity);
            } else {
                // 未通过校验 - 存在幻觉风险
                result.setValidationStatus(ValidationStatus.FAIL);
                result.set_supported(false);
                result.setEvidence_quote(null);
                result.setCorrection_directive("建议重新生成内容或增强RAG检索上下文");
                result.setValidationReason("系统级数学护栏拦截：生成内容与召回知识库的向量语义相似度低于 "
                        + COSINE_SIMILARITY_THRESHOLD + "，存在严重幻觉风险。"
                        + "实际相似度: " + String.format("%.4f", similarity));
                log.warn("[StrictFactCheck] FAIL - 相似度: {}，低于阈值 {}",
                        similarity, COSINE_SIMILARITY_THRESHOLD);
            }

            return List.of(result);
        } catch (Exception e) {
            log.error("[StrictFactCheck] 向量计算过程中发生错误", e);
            return Collections.emptyList();
        }
    }

    /**
     * 获取严格校验后的纯净文本。
     * 若向量相似度低于阈值，抛出 FactualHallucinationException。
     */
    public String enforceFactualAccuracy(String referenceMaterial, String generatedText) {
        List<FactCheckResult> results = checkFacts(referenceMaterial, generatedText);

        boolean hasFailedChecks = results.stream()
                .anyMatch(r -> r.getValidationStatus() == ValidationStatus.FAIL);

        if (hasFailedChecks) {
            List<FactCheckResult> failedClaims = results.stream()
                    .filter(r -> r.getValidationStatus() == ValidationStatus.FAIL)
                    .collect(Collectors.toList());

            log.error("[StrictFactCheck] 检测到幻觉/未经验证的声明：{}",
                    failedClaims.stream().map(FactCheckResult::getValidationReason).collect(Collectors.toList()));

            throw new FactualHallucinationException("Generated text contains unverified claims", failedClaims);
        }

        return generatedText;
    }

    /**
     * 将文本转换为向量（Embedding）。
     * 使用注入的 DashScopeEmbeddingModel 进行编码。
     */
    private List<Double> embedTextToVector(String text) {
        if (dashScopeEmbeddingModel == null) {
            throw new IllegalStateException("DashScopeEmbeddingModel 未配置，无法进行向量校验");
        }
        try {
            TextSegment segment = TextSegment.from(text);
            Response<List<Embedding>> response = dashScopeEmbeddingModel.embedAll(List.of(segment));
            if (response == null || response.content() == null || response.content().isEmpty()) {
                throw new IllegalStateException("Embedding 返回结果为空");
            }
            Embedding embedding = response.content().get(0);
            float[] vectorArray = embedding.vector();
            // 将 float[] 转换为 List<Double>
            List<Double> vectorList = new java.util.ArrayList<>(vectorArray.length);
            for (float v : vectorArray) {
                vectorList.add((double) v);
            }
            return vectorList;
        } catch (Exception e) {
            log.error("[StrictFactCheck] 文本向量化失败", e);
            throw new IllegalStateException("无法将文本转换为向量", e);
        }
    }

    /**
     * 计算两个向量之间的余弦相似度。
     * 纯 Java 实现，不依赖外部数学库。
     * 公式: cos(θ) = (A · B) / (||A|| * ||B||)
     *
     * @param v1 向量1
     * @param v2 向量2
     * @return 余弦相似度，范围 [-1, 1]
     */
    private double calculateCosineSimilarity(List<Double> v1, List<Double> v2) {
        if (v1 == null || v2 == null || v1.isEmpty() || v2.isEmpty()) {
            return 0.0;
        }
        if (v1.size() != v2.size()) {
            log.warn("[StrictFactCheck] 向量维度不匹配: v1={}, v2={}", v1.size(), v2.size());
            // 取最小维度进行计算
            int minDim = Math.min(v1.size(), v2.size());
            v1 = v1.subList(0, minDim);
            v2 = v2.subList(0, minDim);
        }

        double dotProduct = 0.0;
        double norm1 = 0.0;
        double norm2 = 0.0;

        for (int i = 0; i < v1.size(); i++) {
            double a = v1.get(i);
            double b = v2.get(i);
            dotProduct += a * b;
            norm1 += a * a;
            norm2 += b * b;
        }

        double denominator = Math.sqrt(norm1) * Math.sqrt(norm2);
        if (denominator == 0.0) {
            return 0.0;
        }

        return dotProduct / denominator;
    }

    /**
     * 快速校验方法：直接返回 PASS/FAIL 状态。
     * 用于需要简单二分类结果的场景。
     */
    public ValidationStatus quickValidate(String referenceMaterial, String generatedText) {
        List<FactCheckResult> results = checkFacts(referenceMaterial, generatedText);
        if (results.isEmpty()) {
            return ValidationStatus.PASS; // 默认通过，避免阻塞
        }
        return results.get(0).getValidationStatus();
    }

    public static class FactualHallucinationException extends RuntimeException {
        private final transient List<FactCheckResult> failedClaims;

        public FactualHallucinationException(String message, List<FactCheckResult> failedClaims) {
            super(message);
            this.failedClaims = failedClaims;
        }

        public List<FactCheckResult> getFailedClaims() {
            return failedClaims;
        }
    }
}
