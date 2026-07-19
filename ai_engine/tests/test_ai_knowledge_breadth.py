from pathlib import Path


KNOWLEDGE_ROOT = Path(__file__).resolve().parents[1] / "knowledge_base" / "curated"


def test_curated_ai_knowledge_covers_major_domains_and_keeps_cv_depth():
    required_documents = {
        "course_layer/visionary_ai_learning_map.md": ("监督学习", "强化学习", "自然语言处理"),
        "concept_layer/visionary_machine_learning_foundations.md": ("数据泄漏", "泛化", "混淆矩阵"),
        "concept_layer/visionary_nlp_transformers_llm.md": ("Transformer", "大模型", "忠实度"),
        "concept_layer/visionary_reinforcement_learning.md": ("马尔可夫决策过程", "Actor-Critic", "离线强化学习"),
        "concept_layer/visionary_generative_ai_and_rag.md": ("扩散模型", "RAG", "阻断状态"),
        "concept_layer/visionary_responsible_ai_production.md": ("隐私", "监控", "人工复核"),
        "concept_layer/visionary_cv_multimodal_advanced.md": ("目标检测", "多模态", "无效样本"),
    }

    for relative_path, expected_terms in required_documents.items():
        content = (KNOWLEDGE_ROOT / relative_path).read_text(encoding="utf-8")
        assert all(term in content for term in expected_terms), relative_path
        assert "https://" in content, f"{relative_path} must retain traceable source links"
