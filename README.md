# VisionaryTutor / 智眸学伴

VisionaryTutor 是面向中国软件杯 A3 赛题“基于大模型的个性化资源生成与学习多智能体系统开发”的高等教育个性化学习智能体系统。项目以计算机视觉与深度学习课程为示范场景，围绕“对话画像、RAG 知识库、多智能体资源生成、学习路径规划、资源推荐、智能辅导、学习效果评估”构建完整学习闭环。

## 核心能力

- 对话式学习画像：通过自然语言对话抽取专业背景、知识基础、学习目标、认知风格、薄弱点、易错点、学习节奏等画像维度，并随学习行为动态更新。
- 多智能体协同：Planner、Supervisor、Doc、MindMap、Quiz、Reading、Path、Coding、VideoScript、Visualization、Critic 等角色协作生成学习资源。
- 个性化资源生成：支持讲解文档、思维导图、练习题、拓展阅读、学习路径、代码实操、视频脚本/分镜等资源类型。
- RAG 与防幻觉：基于课程知识库检索、引用校验、事实性审查、Critic 复核和内容安全过滤降低幻觉风险。
- 学习路径与推荐：结合画像、学习进度、知识掌握情况和资源使用行为，生成可执行学习路径并推送资源。
- 学习效果评估：通过测试、资源使用、知识追踪、报告视图等形成学习反馈闭环。
- 可交互多模态实验：CNN 卷积实验支持调整 padding/stride、逐步滑窗和输出矩阵；代码资源可编辑并在后端隔离沙箱运行。
- 可审计交付：生成事件记录状态、Agent、模型/Prompt 版本、耗时和降级原因；知识库逐文档记录授权与 SHA-256。

## 技术栈

| 层级 | 技术 |
|---|---|
| 前端 | Vue 3、Vite、Pinia、Element Plus、Mermaid、MediaPipe、Playwright |
| 后端 | Spring Boot 3、Java 17、Spring Security、JPA、Flyway、LangChain4j |
| AI 引擎 | Python 3.11、Chroma、python-pptx、RAG 评估脚本 |
| 数据与基础设施 | MySQL 8、Redis、Chroma、Docker Compose |
| 大模型与服务 | DeepSeek、DashScope/Qwen-VL、讯飞 Spark/TTS、智谱 CogVideoX 等可配置服务 |

## 快速启动

前置环境：JDK 17、Node.js 20+、Python 3.11；完整集成测试还需要 Docker。Maven 由仓库根目录的 Wrapper 自动管理，无需提交本地 Maven 安装目录。

```powershell
# 1. 配置本地密钥
Copy-Item .env.example backend\.env.properties

# 2. 启动后端
.\mvnw.cmd -f backend\pom.xml -DskipTests package
java -jar backend\target\visionary-tutor-backend-0.0.1-SNAPSHOT.jar

# 3. 启动前端
cd frontend
npm ci
npm run dev

# 4. 可选：启动 RAG 知识库
cd ..\ai_engine
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
.\scripts\start_chroma.ps1
.\.venv\Scripts\python.exe document_processor.py
```

也可以使用一键脚本：

```powershell
.\scripts\start-all.ps1
```

## RAG 数据生命周期

知识库源数据与运行时向量索引分开管理：

- `ai_engine/knowledge_base/cleaned/` 仅公开清单允许再分发的知识文档；`processed/` 中保留项目自编的最小示例，完整分块与 `metadata/` 均在本地重建。
- `ai_engine/visionary_chroma_data/` 是 Chroma 运行时数据；`vector_store/`、`embeddings/` 是旧索引或缓存，均不进入 Git 和提交包。
- 删除运行时索引不会删除知识原文，但重新入库完成前，向量检索不可用；后端会降级到 BM25/词项检索。

当前仓库不携带预构建向量库。首次运行或清空 Chroma 后，执行：

```powershell
cd ai_engine
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
.\scripts\start_chroma.ps1

# 在另一个终端执行，读取 processed/ 并重建 visionary_global_knowledge
cd ai_engine
.\.venv\Scripts\python.exe document_processor.py

# 确认 Chroma 服务可用
Invoke-RestMethod http://127.0.0.1:8000/api/v2/heartbeat
```

如需将 Chroma 数据放到其他磁盘，可在启动前设置 `CHROMA_DATA_DIR`。本地嵌入模型首次运行时还会下载模型，完整入库时间取决于机器和知识库规模。

## 项目结构

```text
visionary-tutor/
├─ .mvn/ + mvnw*        标准 Maven Wrapper，仅保存版本配置和启动脚本
├─ backend/              Spring Boot 后端，多智能体编排、画像、RAG、资源、推荐、评估
├─ frontend/             Vue 前端，对话工作台、资源库、学习路径、报告、管理端
├─ ai_engine/            Python AI 引擎，知识库处理、RAG 评估、PPTX 导出
├─ datasets/             知识库授权、SHA-256、再分发资格与重建说明
├─ scripts/              启动、集成冒烟与材料生成脚本
├─ tests/frontend/e2e/   Playwright E2E 测试
└─ loadtest/             JMeter 压测计划与查询数据
```

`target/`、`node_modules/`、`.venv/`、覆盖率报告、运行日志、截图和提交压缩包均为可再生内容，不进入源码结构。

## 开源范围与许可

项目自主开发的源代码采用 [MIT License](LICENSE) 开源。第三方组件、课程语料和云服务不因本项目许可证而改变其原有许可，具体边界见 [THIRD_PARTY.md](THIRD_PARTY.md) 与 `datasets/manifest.json`。

公开仓库仅收录清单中标记为允许再分发的课程语料。授权待核、专有或来源不明确的材料，以及向量索引、派生分块、竞赛提交文档、演示文稿、截图、构建缓存和内部安全报告均不发布。`scripts/build-submission.ps1` 可生成比赛提交包，但产物默认被 Git 忽略。

## 演示与降级

推荐演示主题为“CNN 的 padding、stride 与卷积过程”：依次展示学习画像、带引用的 RAG 问答、多智能体资源生成、CNN 交互实验、代码沙箱、学习路径和前后测报告。

使用 `demo` Profile 时会自动、幂等地初始化演示场景；普通部署可临时设置
`DEMO_SCENARIO_SEED_ON_STARTUP=true` 完成初始化（成功后建议恢复为 `false`）。如需远程手动初始化，必须同时显式设置
`DEMO_SCENARIO_SEED_ENABLED=true` 和高强度随机 `DEMO_SCENARIO_SEED_TOKEN`，默认不开放种子接口。仅当 `demo` Profile 已启动或种子操作成功完成后，以下账号才存在；普通生产 Profile 不会内置默认账号：

| 角色 | 账号 | 密码 |
|---|---|---|
| 学生 | `demo_student` | `Demo@2026` |
| 管理员 | `demo_admin` | `Admin@2026` |

以上仅为本地 `demo` Profile 的公开演示凭据，禁止在生产环境启用或复用。

现场服务异常时采用可解释降级：模型超时使用已生成资源；Chroma 不可用时切换 BM25；视频服务耗时过长时展示已完成样例；所有降级状态必须在界面和答辩中明确说明。

## AI 辅助开发声明

项目由团队主导需求分析、架构设计、业务实现、测试验收、知识库整理和答辩材料定稿。Codex 等编码助手用于代码审阅、局部实现、测试设计和文档整理；DeepSeek、DashScope/Qwen-VL、讯飞与智谱等服务用于系统运行时的画像、资源生成、视觉/语音或视频能力。所有生成内容须经过引用校验、Critic 审查、内容安全控制和人工复核，AI 输出不替代团队判断与责任。

真实密钥仅通过环境变量或本地 `.env.properties` 注入，不进入仓库。组件协议、语料授权和云服务边界见 [THIRD_PARTY.md](THIRD_PARTY.md) 及 `datasets/manifest.json`。

## 质量检查

```powershell
# 后端测试、覆盖率、Checkstyle、SpotBugs
.\mvnw.cmd -f backend\pom.xml verify

# Docker 环境中的真实 MySQL + Redis + Chroma 学习闭环
.\mvnw.cmd -f backend\pom.xml -Pintegration verify

# 前端构建
cd frontend
npm run build

# E2E 测试
npm run test:e2e

# Python 测试
cd ..\ai_engine
python -m pytest tests/ -q

# RAG 评估
python rag_eval_report.py

# 统一验收；有 Docker 时追加 -IncludeIntegration
cd ..
.\scripts\verify-competition.ps1

# 生成许可清单与精简比赛提交包
.\scripts\build-dataset-manifest.ps1
.\scripts\build-submission.ps1
```

RAG 评估会先检查后端与 RAG 高可用检索是否就绪；任何请求失败或质量指标低于门槛都会
返回非零退出码。失败报告不会覆盖 `reports/rag_eval_latest.*` 的最近有效基线。

## 版本信息

- 当前材料版本：v4.0 submission edition
- 更新时间：2026-07-18
- 示例课程方向：计算机视觉 / 深度学习
