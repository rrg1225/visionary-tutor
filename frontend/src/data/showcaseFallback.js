/**
 * 共享教材库前端兜底示例（后端 seed 未就绪时展示，只读预览）。
 */
export const SHOWCASE_TEXTBOOKS_FALLBACK = [
  {
    id: 'demo-cnn',
    title: '卷积神经网络（CNN）入门笔记',
    description: '从感受野、卷积核到特征图，适合零基础快速建立整体框架。',
    subjectTag: 'computer-vision',
    viewCount: 128,
    reviewStatus: 'approved',
    sourceType: 'builtin_sample',
    sourceTitle: 'Visionary Tutor 内置演示内容',
    rightsStatement: '仅用于离线界面演示，不写入个人学习数据或 RAG。',
    rightsConfirmed: true,
    contentMarkdown: `# 卷积神经网络入门

## 为什么需要 CNN？
图像具有**局部相关性**与**平移不变性**，CNN 通过局部连接与权值共享高效提取空间特征。

## 核心组件
- **卷积层**：滑动卷积核提取局部模式
- **激活函数**：ReLU 引入非线性
- **池化层**：降采样、扩大感受野

## 输出尺寸
\`O = floor((I - K + 2P) / S) + 1\``,
  },
  {
    id: 'demo-padding',
    title: 'Padding 与 Stride 图解总结',
    description: '理解填充与步长如何改变特征图尺寸。',
    subjectTag: 'computer-vision',
    viewCount: 96,
    reviewStatus: 'approved',
    sourceType: 'builtin_sample',
    sourceTitle: 'Visionary Tutor 内置演示内容',
    rightsStatement: '仅用于离线界面演示，不写入个人学习数据或 RAG。',
    rightsConfirmed: true,
    contentMarkdown: `# Padding 与 Stride

**Padding** 在边缘补零，保留边界信息；**Stride** 控制卷积核滑动步幅，影响输出尺寸与计算量。`,
  },
  {
    id: 'demo-yolo',
    title: 'YOLO 目标检测学习笔记',
    description: '单阶段检测：网格预测 + 边界框回归。',
    subjectTag: 'object-detection',
    viewCount: 112,
    reviewStatus: 'approved',
    sourceType: 'builtin_sample',
    sourceTitle: 'Visionary Tutor 内置演示内容',
    rightsStatement: '仅用于离线界面演示，不写入个人学习数据或 RAG。',
    rightsConfirmed: true,
    contentMarkdown: `# YOLO 入门

将检测视为回归问题，一次前向同时预测类别与框坐标。适合实时场景。`,
  },
  {
    id: 'demo-vit',
    title: 'Vision Transformer（ViT）精读摘要',
    description: 'Patch 嵌入 + 位置编码，Transformer 做图像分类。',
    subjectTag: 'computer-vision',
    viewCount: 89,
    reviewStatus: 'approved',
    sourceType: 'builtin_sample',
    sourceTitle: 'Visionary Tutor 内置演示内容',
    rightsStatement: '仅用于离线界面演示，不写入个人学习数据或 RAG。',
    rightsConfirmed: true,
    contentMarkdown: `# ViT 摘要

将图像切分为 Patch 序列，经 Transformer Encoder 提取全局表示，[CLS] token 用于分类。`,
  },
]
