package com.visionary.service;

import com.visionary.dto.ResourceGenerationRequest;
import com.visionary.entity.LearningSession;
import com.visionary.entity.SharedTextbook;
import com.visionary.entity.User;
import com.visionary.repository.GeneratedArtifactRepository;
import com.visionary.repository.LearningSessionRepository;
import com.visionary.repository.SharedTextbookRepository;
import com.visionary.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 注入示例教材与示例资源，避免资源库 / 共享教材库首屏空态。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShowcaseContentSeedService {

    public static final String SHOWCASE_SESSION_TOPIC = "【示例资源库】CNN 卷积神经网络专题";
    public static final String SHOWCASE_RUN_PREFIX = "showcase-seed-";

    private final SharedTextbookRepository textbookRepository;
    private final LearningSessionRepository learningSessionRepository;
    private final GeneratedArtifactRepository artifactRepository;
    private final UserRepository userRepository;
    private final LocalMockService localMockService;

    @Value("${visionary.showcase.seed-owner-username:}")
    private String seedOwnerUsername;

    @Transactional
    public void seedIfEmpty() {
        Long ownerId = resolveSeedOwnerId();
        int textbooks = seedTextbooksIfEmpty(ownerId);
        int artifacts = seedShowcaseResourcesIfEmpty(ownerId);
        if (textbooks > 0 || artifacts > 0) {
            log.info("[showcase-seed] 完成：textbooks={}, artifacts={}", textbooks, artifacts);
        }
    }

    public Optional<Long> findShowcaseSessionId() {
        return learningSessionRepository.findAll().stream()
                .filter(session -> SHOWCASE_SESSION_TOPIC.equals(session.getTopic()))
                .map(LearningSession::getId)
                .findFirst();
    }

    private Long resolveSeedOwnerId() {
        if (seedOwnerUsername != null && !seedOwnerUsername.isBlank()) {
            Optional<User> named = userRepository.findByUsername(seedOwnerUsername.trim());
            if (named.isPresent()) {
                return named.get().getId();
            }
        }
        return userRepository.findAll().stream()
                .findFirst()
                .map(User::getId)
                .orElse(1L);
    }

    private int seedTextbooksIfEmpty(Long ownerId) {
        if (textbookRepository.count() > 0) {
            return 0;
        }
        int count = 0;
        for (TextbookSeed seed : TextbookSeed.catalog()) {
            SharedTextbook book = new SharedTextbook();
            book.setOwnerUserId(ownerId);
            book.setTitle(seed.title());
            book.setDescription(seed.description());
            book.setContentMarkdown(seed.contentMarkdown());
            book.setSubjectTag(seed.subjectTag());
            book.setVisibility("public");
            book.setReviewStatus("approved");
            book.setReviewedBy(ownerId);
            book.setReviewedAt(LocalDateTime.now());
            book.setViewCount(seed.viewCount());
            textbookRepository.save(book);
            count++;
        }
        return count;
    }

    private int seedShowcaseResourcesIfEmpty(Long ownerId) {
        boolean hasShowcase = learningSessionRepository.findAll().stream()
                .anyMatch(session -> SHOWCASE_SESSION_TOPIC.equals(session.getTopic()));
        if (hasShowcase) {
            return 0;
        }

        LearningSession session = new LearningSession();
        session.setUserId(ownerId);
        session.setTopic(SHOWCASE_SESSION_TOPIC);
        session.setStatus(LearningSession.SessionStatus.ACTIVE);
        session.setCurrentPhase(LearningSession.LearningPhase.RESOURCE_GENERATION);
        session.setConversationSummary("VisionaryTutor 内置示例资源，供浏览与演示。");
        LearningSession saved = learningSessionRepository.save(session);

        String runId = SHOWCASE_RUN_PREFIX + saved.getId();
        localMockService.generateShowcaseResources(
                runId,
                saved,
                "CNN 卷积、Padding 与 Stride",
                new ResourceGenerationRequest(
                        saved.getId(),
                        "CNN 卷积、Padding 与 Stride",
                        "{\"learningStyle\":\"visual\",\"goal\":\"理解 CNN 基础\"}",
                        "[\"卷积尺寸推导\",\"Padding/Stride\"]",
                        "专注",
                        null
                )
        );
        return (int) artifactRepository.findByLearningSessionIdOrderByGmtCreatedDesc(saved.getId()).size();
    }

    private record TextbookSeed(
            String title,
            String description,
            String subjectTag,
            String contentMarkdown,
            int viewCount
    ) {
        static List<TextbookSeed> catalog() {
            return List.of(
                    entry(
                            "卷积神经网络（CNN）入门笔记",
                            "从感受野、卷积核到特征图，适合零基础快速建立整体框架。",
                            "computer-vision",
                            """
                            # 卷积神经网络入门

                            ## 为什么需要 CNN？
                            图像具有**局部相关性**与**平移不变性**，全连接网络参数量过大。CNN 通过局部连接与权值共享高效提取空间特征。

                            ## 核心组件
                            - **卷积层**：滑动卷积核提取局部模式
                            - **激活函数**：引入非线性（ReLU 最常用）
                            - **池化层**：降采样、扩大感受野
                            - **全连接层**：将特征映射到类别空间

                            ## 输出尺寸公式
                            `O = floor((I - K + 2P) / S) + 1`

                            | 符号 | 含义 |
                            |------|------|
                            | I | 输入边长 |
                            | K | 卷积核大小 |
                            | P | Padding |
                            | S | Stride |

                            ## 学习建议
                            1. 手算 5×5 输入 + 3×3 卷积
                            2. 对比 padding=0 与 padding=1 的输出
                            3. 用 PyTorch 打印张量形状验证
                            """,
                            128
                    ),
                    entry(
                            "Padding 与 Stride 图解总结",
                            "用表格与示意图理解填充、步长如何改变特征图尺寸。",
                            "computer-vision",
                            """
                            # Padding 与 Stride

                            ## Padding（填充）
                            在输入边缘补零，**保留边界信息**，控制输出尺寸。

                            - **Valid**：不填充，输出缩小
                            - **Same**：填充使输出与输入同尺寸（S=1, K 奇数时）

                            ## Stride（步长）
                            卷积核每次滑动的像素数。Stride 越大，输出越小、计算越快。

                            ## 常见面试题
                            1. 输入 32×32，K=3, S=1, P=1 → 输出？
                            2. 为什么深层网络需要 Padding？
                            3. Stride 与 Pooling 的区别？

                            > 建议配合动画演示理解「卷积核滑动」过程。
                            """,
                            96
                    ),
                    entry(
                            "反向传播链式法则速查",
                            "深度学习数学基础：从标量损失到各层梯度的传递。",
                            "deep-learning",
                            """
                            # 反向传播速查

                            ## 链式法则
                            若 `L = f(g(h(x)))`，则 `dL/dx = dL/df · df/dg · dg/dh · dh/dx`

                            ## 计算图视角
                            - **前向**：自输入逐层计算输出
                            - **反向**：自损失逐层累积梯度

                            ## CNN 中的注意点
                            - 卷积的反向对应 **转置卷积** 或 col2im
                            - Pooling 反向只传递到「被选中的」位置
                            - BatchNorm 在 train/eval 模式行为不同

                            ## 调试技巧
                            - 检查梯度是否为 0 或 NaN
                            - 用小 batch、小网络验证手写梯度
                            """,
                            74
                    ),
                    entry(
                            "YOLO 目标检测学习笔记",
                            "单阶段检测器核心思想：网格预测 + 边界框回归。",
                            "object-detection",
                            """
                            # YOLO 入门

                            ## 核心思想
                            将检测视为**回归问题**：一次前向同时预测类别与框坐标。

                            ## 输出表示
                            每个网格预测 B 个边界框 + 置信度 + C 个类别概率。

                            ## 损失组成
                            1. 坐标损失（x, y, w, h）
                            2. 置信度损失（objectness）
                            3. 分类损失

                            ## 与 R-CNN 系列对比
                            | 方法 | 速度 | 精度 |
                            |------|------|------|
                            | Two-stage | 较慢 | 通常更高 |
                            | YOLO | 快 | 持续迭代提升 |

                            ## 实践建议
                            从 YOLOv5/v8 官方 demo 跑通 COCO 子集。
                            """,
                            112
                    ),
                    entry(
                            "计算机视觉数据增强清单",
                            "训练集扩充常用手段与适用场景。",
                            "computer-vision",
                            """
                            # 数据增强清单

                            ## 几何变换
                            - 随机裁剪 / 缩放
                            - 水平翻转
                            - 小角度旋转

                            ## 颜色变换
                            - 亮度、对比度、饱和度抖动
                            - 灰度化（多任务时可作正则）

                            ## 高级策略
                            - **Mixup** / **CutMix**
                            - **AutoAugment** 搜索策略

                            ## 注意事项
                            - 增强应匹配部署场景（如医学影像慎用大幅旋转）
                            - 验证集不做随机增强，保证评估稳定
                            """,
                            58
                    ),
                    entry(
                            "Vision Transformer（ViT）精读摘要",
                            "将 Transformer 引入图像分类：Patch 嵌入 + 位置编码。",
                            "computer-vision",
                            """
                            # Vision Transformer 摘要

                            ## 流程
                            1. 将图像切分为固定大小 Patch
                            2. 线性投影为 Token 序列
                            3. 加 **[CLS]** 与位置编码
                            4. 标准 Transformer Encoder
                            5. 用 [CLS] 表示做分类

                            ## 与 CNN 的差异
                            - 全局自注意力，早期需大数据预训练
                            - 归纳偏置较弱，数据少时 CNN 往往更稳

                            ## 延伸阅读
                            - Swin Transformer（层次化窗口注意力）
                            - DeiT（知识蒸馏训练 ViT）
                            """,
                            89
                    )
            );
        }

        private static TextbookSeed entry(
                String title,
                String description,
                String tag,
                String markdown,
                int views
        ) {
            return new TextbookSeed(title, description, tag, markdown.strip(), views);
        }
    }
}
