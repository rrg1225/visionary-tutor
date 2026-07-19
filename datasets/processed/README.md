# 派生数据重建

`ai_engine/knowledge_base/processed`、`embeddings` 和 `vector_store` 是构建产物，不作为源码或原始数据分发。

重建步骤：

1. 依据 `datasets/manifest.json` 校验规范化文档 SHA-256。
2. 运行 `python ai_engine/document_processor.py` 进行语义切片。
3. 使用与后端一致的 embedding provider、model 和 dimension 写入 Chroma。
4. 执行 `python ai_engine/rag_eval.py`，确认召回、引用、拒答和延迟门禁。

重建所需的参数、工具版本和金标集均由仓库管理，因此提交包无需携带数万份重复切片或 SQLite/HNSW 文件。
