# 第三方开源组件与协议说明

VisionaryTutor 在开发过程中使用了以下开源组件、框架、课程语料和云服务。提交比赛材料时应保留本文件，并在答辩 PPT 或系统开发说明书中显著标注。

## 开源组件

| 名称 | 用途 | 来源 | 协议 |
|---|---|---|---|
| Spring Boot | 后端框架 | https://spring.io/projects/spring-boot | Apache-2.0 |
| Spring Security | 认证与鉴权 | https://spring.io/projects/spring-security | Apache-2.0 |
| Flyway | 数据库迁移 | https://flywaydb.org | Apache-2.0 |
| LangChain4j | LLM/RAG 集成 | https://github.com/langchain4j/langchain4j | Apache-2.0 |
| Chroma | 向量数据库 | https://www.trychroma.com | Apache-2.0 |
| Vue 3 | 前端框架 | https://vuejs.org | MIT |
| Vite | 前端构建工具 | https://vitejs.dev | MIT |
| Pinia | 前端状态管理 | https://pinia.vuejs.org | MIT |
| Element Plus | 前端组件库 | https://element-plus.org | MIT |
| Axios | HTTP 客户端 | https://axios-http.com | MIT |
| Mermaid | 思维导图与流程图渲染 | https://mermaid.js.org | MIT |
| MediaPipe Tasks Vision | 本地视觉辅助能力 | https://developers.google.com/mediapipe | Apache-2.0 |
| Playwright | 前端 E2E 测试 | https://playwright.dev | Apache-2.0 |
| python-docx / python-pptx | 文档与 PPTX 生成 | https://python-docx.readthedocs.io / https://python-pptx.readthedocs.io | MIT |

## 课程语料与知识库来源

| 名称 | 用途 | 来源 | 协议/说明 |
|---|---|---|---|
| Dive into Deep Learning (D2L) | 深度学习课程知识库 | https://d2l.ai | CC-BY-SA-4.0 |
| CS231n 相关公开资料 | 计算机视觉知识库 | Stanford CS231n 公开课程资料及授权翻译材料 | 按原资料声明使用 |
| PyTorch Tutorials | 代码实操与深度学习实践语料 | https://pytorch.org/tutorials | BSD-style / 原项目协议 |
| 公开算法与机器学习资料 | RAG 测试与课程扩展 | 项目整理的公开学习材料 | 按来源分别遵守 |

逐文档来源、SHA-256 与提交资格以 `datasets/manifest.json` 为准。正式提交包采用“默认拒绝分发”策略：CS229、CS231n 翻译材料在授权未核清前不进入提交包；商业出版书籍及其转换稿禁止进入提交包；`processed`、向量索引等派生数据由脚本重建而不分发。

## 大模型与云服务 API

以下服务不属于开源组件，需按各服务商协议配置 API Key 后使用：

| 服务 | 用途 | 说明 |
|---|---|---|
| DeepSeek API | 对话、资源生成、画像抽取、Critic 审查 | 按服务商协议使用 |
| DashScope / Qwen-VL | Embedding、视觉作业评估 | 按阿里云服务协议使用 |
| 讯飞 Spark / TTS | 语音、学习状态分析、AI 辅助能力展示 | 按科大讯飞开放平台协议使用 |
| 智谱 CogVideoX | 教学短视频生成接口接入 | 按服务商协议使用 |

## 使用边界

- API Key 仅通过本地环境变量或 `backend/.env.properties` 注入，禁止提交真实密钥。
- 课程语料用于比赛、教学与演示场景；如进入商业化，应重新核对每类语料授权。
- 使用生成式 AI 产出的内容需经过系统内 Critic 审查和人工复核。
- 团队自主开发的业务代码、智能体编排逻辑、产品交互和提交材料归团队所有。
