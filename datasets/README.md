# VisionaryTutor 数据集交付层

本目录记录知识库文档的来源、许可、校验摘要和再分发资格。运行时数据仍从 `ai_engine/knowledge_base` 读取；公开仓库只收录授权状态明确且可追溯的规范化文档，不携带原始抓取副本、派生切片或向量数据库。

## 目录约定

- `manifest.json`：逐文档记录来源、许可状态、SHA-256、处理方式和再分发资格。
- `ai_engine/knowledge_base/cleaned/`：允许公开再分发的规范化知识文档。
- `ai_engine/knowledge_base/processed/`：项目自编的最小教学示例；完整派生切片在本地重建。
- `scripts/build-dataset-manifest.ps1`：重新生成并校验许可清单。
- `scripts/build-submission.ps1`：构建精简、可复核的比赛提交包。

## 发布原则

1. 仅发布 `redistribution=allowed` 或 `allowed_noncommercial` 且许可信息完整的文档。
2. 授权不明确的课程材料和专有书籍默认标记为 `excluded`，即使本地开发环境中存在也不上传。
3. `rag_md_files`、完整 `processed`、`metadata`、`embeddings` 和 `vector_store` 均为可再生数据，不进入公开仓库。
4. 每个公开知识文档都必须能通过 `manifest.json` 中的 SHA-256 追溯到来源与许可。

重新生成清单：

```powershell
.\scripts\build-dataset-manifest.ps1
```

构建比赛提交包：

```powershell
.\scripts\build-submission.ps1
```
