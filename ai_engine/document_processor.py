"""RAG vector processing for VisionaryTutor Engine.

Ingestion source: ``knowledge_base/processed/`` (inside ai_engine/) — strictly 7 teaching layers.
All chunks are written to the standalone Chroma HTTP service (default ``http://localhost:8000``)
into collection ``visionary_global_knowledge`` (Java ``VectorDbConfig.collectionName``).
Layer routing uses document metadata ``chroma_layer`` / ``layer``.
"""

from __future__ import annotations

import json
import logging
import os
import re
import sys
import urllib.error
import urllib.request
from collections import defaultdict
from pathlib import Path
from typing import Any, Iterator

import chromadb
from langchain_chroma import Chroma
from langchain_core.documents import Document
from langchain_core.embeddings import Embeddings


if hasattr(sys.stdout, "reconfigure"):
    try:
        sys.stdout.reconfigure(encoding="utf-8")
        sys.stderr.reconfigure(encoding="utf-8")
    except (AttributeError, OSError, ValueError):
        pass

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
)
logger = logging.getLogger(__name__)

ENGINE_ROOT = Path(__file__).resolve().parent
# knowledge_base/ lives inside ai_engine/ (next to the scripts), not at the repo root.
KNOWLEDGE_BASE_ROOT = Path(
    os.getenv("KNOWLEDGE_BASE_ROOT", str(ENGINE_ROOT / "knowledge_base"))
)
PROCESSED_ROOT = KNOWLEDGE_BASE_ROOT / "processed"
CURATED_ROOT = KNOWLEDGE_BASE_ROOT / "curated"
MANIFEST_PATH = KNOWLEDGE_BASE_ROOT / "metadata" / "chunk_manifest.json"

TEXT_READ_ENCODING = "utf-8"
TEXT_READ_ERRORS = "replace"

# Educational 7-layer taxonomy used by processed knowledge-base documents.
TEACHING_LAYERS: tuple[str, ...] = (
    "course_layer",
    "concept_layer",
    "math_layer",
    "algorithm_layer",
    "code_layer",
    "exercise_layer",
    "assessment_layer",
)

# 高校教学场景优化分隔符：优先保护代码块和LaTeX公式完整性
# 优先级：段落 > 章节 > 公式边界 > 代码块边界 > 中文标点 > 空格
CHINESE_AWARE_SEPARATORS = [
    # 1. 代码块边界（防止Python代码被切断）
    "\n```\n", "\n```\r\n",
    # 2. LaTeX公式边界（防止矩阵/微积分公式被切断）
    "\n$$\n", "\n$$\r\n", "$$\n", "$$\r\n",
    "\n\\[\n", "\n\\]\n", "\\[", "\\]",
    # 3. 题目块边界（防止题目和解答分离）
    "\n**题目**", "\n**例题**", "\n**练习**", "\n**解答**", "\n**解析**",
    # 4. 章节边界
    "\n## ", "\n### ", "\n#### ",
    # 5. 段落边界
    "\n\n", "\n",
    # 6. 中文自然断句
    "。\n", "。", "！", "？", "；", "，",
    # 7. 空格和字符
    " ", "",
]

# 保护块类型映射
PROTECTED_BLOCK_MARKERS = {
    "code": ["```", "~~~"],
    "latex_display": ["$$", r"\[", r"\]"],
    "latex_inline": [r"\(", r"\)"],
    "exercise": ["**题目**", "**例题**", "**练习**"],
    "solution": ["**解答**", "**解析**", "**答案**"],
}

DEFAULT_CHUNK_SIZE = int(os.getenv("RAG_CHUNK_SIZE", "1000"))
DEFAULT_CHUNK_OVERLAP = int(os.getenv("RAG_CHUNK_OVERLAP", "200"))
# Chroma HTTP server — aligned with backend application.yml → vector.db.*
CHROMA_HOST = os.getenv("CHROMA_HOST", "localhost")
CHROMA_PORT = int(os.getenv("CHROMA_PORT", "8000"))
CHROMA_TENANT = os.getenv("CHROMA_TENANT_NAME", os.getenv("CHROMA_TENANT", "default_tenant"))
CHROMA_DATABASE = os.getenv(
    "CHROMA_DATABASE_NAME", os.getenv("CHROMA_DATABASE", "default_database")
)
EMBEDDING_PROVIDER = os.getenv("EMBEDDING_PROVIDER", "local").lower()
EMBEDDING_MODEL_NAME = os.getenv("EMBEDDING_MODEL_NAME", "all-MiniLM-L6-v2")
EMBEDDING_BATCH_SIZE = int(os.getenv("EMBEDDING_BATCH_SIZE", "32"))
DASHSCOPE_BASE_URL = os.getenv(
    "DASHSCOPE_BASE_URL",
    os.getenv("QWEN_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1"),
)
DASHSCOPE_API_KEY = os.getenv("DASHSCOPE_API_KEY", os.getenv("QWEN_VL_MAX_KEY", ""))
# Fixed — must match Java VectorDbConfig.collectionName (not overridable)
GLOBAL_COLLECTION_NAME = "visionary_global_knowledge"

# 7 pedagogical layers -> 3 Chroma collections (Java backend contract)
PEDAGOGICAL_LAYER_TO_CHROMA: dict[str, str] = {
    "course_layer": "application_layer",
    "concept_layer": "application_layer",
    "exercise_layer": "application_layer",
    "assessment_layer": "application_layer",
    "algorithm_layer": "algorithm_layer",
    "code_layer": "algorithm_layer",
    "math_layer": "math_layer",
}


class SentenceTransformerEmbeddings(Embeddings):
    def __init__(self, model_name: str, batch_size: int = 32) -> None:
        from sentence_transformers import SentenceTransformer

        self._model = SentenceTransformer(model_name)
        self._batch_size = batch_size

    def embed_documents(self, texts: list[str]) -> list[list[float]]:
        vectors = self._model.encode(
            texts,
            batch_size=self._batch_size,
            normalize_embeddings=True,
            show_progress_bar=len(texts) > 50,
        )
        return vectors.tolist()

    def embed_query(self, text: str) -> list[float]:
        return self._model.encode([text], normalize_embeddings=True)[0].tolist()


class DashScopeEmbeddings(Embeddings):
    """OpenAI-compatible DashScope embeddings for Chinese RAG ingestion."""

    def __init__(self, base_url: str, api_key: str, model_name: str, batch_size: int = 16) -> None:
        if not api_key:
            raise RuntimeError("DASHSCOPE_API_KEY or QWEN_VL_MAX_KEY is required for EMBEDDING_PROVIDER=dashscope")
        self._endpoint = base_url.rstrip("/") + "/embeddings"
        self._api_key = api_key
        self._model_name = model_name
        self._batch_size = max(1, batch_size)

    def embed_documents(self, texts: list[str]) -> list[list[float]]:
        vectors: list[list[float]] = []
        for start in range(0, len(texts), self._batch_size):
            vectors.extend(self._embed_batch(texts[start:start + self._batch_size]))
        return vectors

    def embed_query(self, text: str) -> list[float]:
        return self._embed_batch([text])[0]

    def _embed_batch(self, texts: list[str]) -> list[list[float]]:
        payload = json.dumps({"model": self._model_name, "input": texts}).encode("utf-8")
        request = urllib.request.Request(
            self._endpoint,
            data=payload,
            headers={
                "Authorization": f"Bearer {self._api_key}",
                "Content-Type": "application/json",
            },
            method="POST",
        )
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                result = json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as exc:
            detail = exc.read().decode("utf-8", errors="replace")
            raise RuntimeError(f"DashScope embeddings failed: HTTP {exc.code} {detail}") from exc
        return [item["embedding"] for item in result["data"]]


def create_embeddings() -> Embeddings:
    if EMBEDDING_PROVIDER in {"dashscope", "qwen"}:
        logger.info("Using DashScope embeddings: model=%s", EMBEDDING_MODEL_NAME)
        return DashScopeEmbeddings(
            DASHSCOPE_BASE_URL,
            DASHSCOPE_API_KEY,
            EMBEDDING_MODEL_NAME,
            batch_size=EMBEDDING_BATCH_SIZE,
        )
    logger.info("Using local SentenceTransformer embeddings: model=%s", EMBEDDING_MODEL_NAME)
    return SentenceTransformerEmbeddings(EMBEDDING_MODEL_NAME, batch_size=EMBEDDING_BATCH_SIZE)


def read_text_file(path: Path) -> str:
    return path.read_text(encoding=TEXT_READ_ENCODING, errors=TEXT_READ_ERRORS)


def detect_content_structure(text: str) -> dict[str, list[dict[str, Any]]]:
    """
    识别教学内容的层次结构：章节 -> 知识点 -> 公式 -> 代码 -> 题目。
    返回按类型分组的位置信息，用于智能分块时保护重要边界。
    """
    import re
    
    structure: dict[str, list[dict[str, Any]]] = {
        "chapters": [],      # 章节标题
        "knowledge_points": [], # 知识点段落
        "formulas": [],      # LaTeX公式
        "code_blocks": [],   # 代码块
        "tables": [],        # Markdown表格
        "exercises": [],     # 练习题
    }
    
    # 检测章节标题
    chapter_patterns = [
        r"^(#{1,4})\s+(.+)$",  # Markdown标题
        r"^【(.+)】$",          # 中文章节标记
    ]
    for pattern in chapter_patterns:
        for match in re.finditer(pattern, text, re.MULTILINE):
            structure["chapters"].append({
                "start": match.start(),
                "end": match.end(),
                "level": len(match.group(1)) if match.group(1).startswith("#") else 2,
                "title": match.group(2).strip(),
            })
    
    # 检测代码块
    code_pattern = r"```[\w]*\n[\s\S]*?```"
    for match in re.finditer(code_pattern, text):
        structure["code_blocks"].append({
            "start": match.start(),
            "end": match.end(),
            "content": match.group(0),
        })

    table_pattern = r"(?:^\|.*\|\s*$\n?){2,}"
    for match in re.finditer(table_pattern, text, re.MULTILINE):
        if "---" in match.group(0):
            structure["tables"].append({
                "start": match.start(),
                "end": match.end(),
                "content": match.group(0),
            })
    
    # 检测LaTeX显示公式 $$...$$
    latex_display_pattern = r"\$\$[\s\S]*?\$\$"
    for match in re.finditer(latex_display_pattern, text):
        structure["formulas"].append({
            "start": match.start(),
            "end": match.end(),
            "type": "display",
            "content": match.group(0),
        })
    
    # 检测LaTeX行内公式 $...$（排除 $$ 的情况）
    latex_inline_pattern = r"(?<!\$)\$(?!\$)[^\$]+\$"
    for match in re.finditer(latex_inline_pattern, text):
        # 确保不在代码块内
        in_code_block = any(
            cb["start"] <= match.start() <= cb["end"]
            for cb in structure["code_blocks"]
        )
        if not in_code_block:
            structure["formulas"].append({
                "start": match.start(),
                "end": match.end(),
                "type": "inline",
                "content": match.group(0),
            })
    
    # 检测LaTeX环境 \[...\] 和 \(...\)
    latex_env_patterns = [
        (r"\\\[[\s\S]*?\\\]", "display"),
        (r"\\\([\s\S]*?\\\)", "inline"),
    ]
    for pattern, ftype in latex_env_patterns:
        for match in re.finditer(pattern, text):
            in_existing = any(
                f["start"] <= match.start() <= f["end"]
                for f in structure["formulas"]
            )
            if not in_existing:
                structure["formulas"].append({
                    "start": match.start(),
                    "end": match.end(),
                    "type": ftype,
                    "content": match.group(0),
                })
    
    # 检测题目区域
    exercise_patterns = [
        r"\*\*题目\*\*[\s\S]*?(?=\*\*解答\*\*|\*\*解析\*\*|\n#{1,4}|$)",
        r"\*\*例题\*\*[\s\S]*?(?=\*\*解答\*\*|\*\*解析\*\*|\n#{1,4}|$)",
        r"\*\*练习\*\*[\s\S]*?(?=\*\*答案\*\*|\n#{1,4}|$)",
    ]
    for pattern in exercise_patterns:
        for match in re.finditer(pattern, text, re.IGNORECASE):
            # 确保不跨越代码块
            in_code = any(
                cb["start"] <= match.start() <= cb["end"] or
                cb["start"] <= match.end() <= cb["end"]
                for cb in structure["code_blocks"]
            )
            if not in_code:
                structure["exercises"].append({
                    "start": match.start(),
                    "end": match.end(),
                    "type": "exercise",
                    "content": match.group(0)[:200] + "..." if len(match.group(0)) > 200 else match.group(0),
                })
    
    return structure


def is_position_in_protected_block(pos: int, protected_ranges: list[tuple[int, int]]) -> bool:
    """检查位置是否在任何保护范围内。"""
    return any(start <= pos <= end for start, end in protected_ranges)


def find_safe_split_positions(
    text: str,
    preferred_size: int,
    overlap_size: int,
    protected_ranges: list[tuple[int, int]]
) -> list[int]:
    """
    在保护块之外寻找安全的切分位置。
    优先使用 CHINESE_AWARE_SEPARATORS 中的高优先级分隔符。
    """
    import re
    
    split_positions = [0]
    current_pos = preferred_size
    text_len = len(text)
    
    while current_pos < text_len:
        # 如果当前位置在保护块内，向后移动到保护块之后
        while current_pos < text_len and is_position_in_protected_block(current_pos, protected_ranges):
            # 找到包含当前位置的保护块，跳到其后
            for start, end in protected_ranges:
                if start <= current_pos <= end:
                    current_pos = end
                    break
            else:
                current_pos += 1
        
        if current_pos >= text_len:
            break
        
        # 寻找最近的分隔符
        best_split = None
        best_priority = float('inf')
        
        for i, sep in enumerate(CHINESE_AWARE_SEPARATORS):
            # 在当前位置附近查找分隔符
            search_start = max(0, current_pos - preferred_size // 2)
            search_end = min(text_len, current_pos + preferred_size // 2)
            
            # 查找所有匹配
            pos = text.find(sep, search_start)
            while pos != -1 and pos < search_end:
                if not is_position_in_protected_block(pos, protected_ranges):
                    # 分隔符优先级 = 列表索引
                    if i < best_priority or (i == best_priority and abs(pos - current_pos) < abs(best_split - current_pos)):
                        best_priority = i
                        best_split = pos + len(sep)
                    break
                pos = text.find(sep, pos + 1)
        
        if best_split is None:
            # 没找到合适的分隔符，强制切分（确保不在保护块内）
            best_split = current_pos
            while best_split < text_len and is_position_in_protected_block(best_split, protected_ranges):
                best_split += 1
        
        if best_split <= split_positions[-1]:
            # 避免死循环
            best_split = min(split_positions[-1] + 100, text_len)
        
        split_positions.append(best_split)
        current_pos = best_split + preferred_size - overlap_size
    
    if split_positions[-1] < text_len:
        split_positions.append(text_len)
    
    return split_positions


def strip_yaml_front_matter(text: str) -> str:
    if text.startswith("---"):
        end = text.find("\n---", 3)
        if end != -1:
            return text[end + 4 :].lstrip("\n")
    return text


def chroma_metadata(entry: dict[str, Any]) -> dict[str, str | int | float]:
    """
    构建Chroma元数据，包含高校教学闭环所需的完整信息。
    
    核心字段：
    - source_layer: 原始教学层（course/concept/code/math等）
    - difficulty: 难度等级（1-5）
    - error_tags: 错因标签，用于诊断（JSON数组）
    - chapter_title: 所属章节标题
    - content_types: 内容类型（code/formula/exercise）
    """
    concepts = entry.get("concepts") or []
    concepts_str = (
        json.dumps(concepts, ensure_ascii=False)
        if isinstance(concepts, list)
        else str(concepts)
    )
    
    layer = str(entry.get("layer", "concept_layer"))
    
    # 错因标签：支持列表或逗号分隔字符串
    error_tags = entry.get("error_tags") or entry.get("mistake_types") or []
    if isinstance(error_tags, list):
        error_tags_str = json.dumps(error_tags, ensure_ascii=False)
    elif isinstance(error_tags, str):
        # 处理逗号分隔的错因标签
        tags_list = [t.strip() for t in error_tags.split(",") if t.strip()]
        error_tags_str = json.dumps(tags_list, ensure_ascii=False)
    else:
        error_tags_str = "[]"
    
    # 知识点层级路径（用于高级检索过滤）
    knowledge_path = entry.get("knowledge_path") or ""
    if not knowledge_path and "chapter_title" in entry:
        knowledge_path = f"{entry.get('chapter_title', '')}>{entry.get('title', '')}"
    
    meta: dict[str, str | int | float] = {
        "chunk_id": str(entry.get("chunk_id", "")),
        "title": str(entry.get("title", "")),
        "layer": layer,
        "chroma_layer": PEDAGOGICAL_LAYER_TO_CHROMA.get(layer, "application_layer"),
        "source_layer": layer,  # 原始层，用于精确过滤
        "concepts": concepts_str,
        "difficulty": int(entry.get("difficulty", 3)),
        "error_tags": error_tags_str,
        "source_file": str(entry.get("source_file", "")),
        "resource_type": str(entry.get("resource_type", "概念")),
        "chapter_title": str(entry.get("chapter_title", "")),
        "knowledge_path": str(knowledge_path),
    }
    
    # 可选元数据字段
    if "content_types" in entry:
        meta["content_types"] = str(entry.get("content_types", ""))
    if "chapter_level" in entry:
        meta["chapter_level"] = int(entry.get("chapter_level", 1))
    if "estimated_time_min" in entry:
        meta["estimated_time_min"] = int(entry.get("estimated_time_min", 0))
    
    return meta


def iter_processed_markdown_recursive(layer: str) -> Iterator[Path]:
    """Walk generated and hand-curated documents for one teaching layer."""
    for source_root in (PROCESSED_ROOT, CURATED_ROOT):
        layer_dir = source_root / layer
        if not layer_dir.is_dir():
            continue
        for path in sorted(layer_dir.rglob("*.md")):
            if path.is_file():
                yield path


def load_documents_from_manifest() -> list[Document]:
    """
    从 manifest 加载文档，支持高校教学闭环的完整元数据。
    
    预期 manifest 格式：
    {
        "chunks": [
            {
                "chunk_id": "unique-id",
                "layer": "code_layer",
                "title": "快速排序实现",
                "chapter_title": "排序算法",
                "difficulty": 4,
                "error_tags": ["边界条件遗漏", "递归终止条件错误"],
                "concepts": ["quick sort", "recursion", "pivot"],
                "knowledge_path": "算法>排序>快速排序",
                "resource_type": "代码案例"
            }
        ]
    }
    """
    if not MANIFEST_PATH.is_file():
        logger.warning("Manifest not found: %s", MANIFEST_PATH)
        return []

    try:
        manifest = json.loads(read_text_file(MANIFEST_PATH))
    except json.JSONDecodeError as exc:
        logger.error("Invalid manifest: %s", exc)
        return []

    documents: list[Document] = []
    stats = {"with_error_tags": 0, "with_code": 0, "with_formula": 0}
    
    for entry in manifest.get("chunks", []):
        chunk_id = entry.get("chunk_id")
        layer = entry.get("layer", "concept_layer")
        if not chunk_id or layer not in TEACHING_LAYERS:
            continue

        chunk_path = PROCESSED_ROOT / layer / f"{chunk_id}.md"
        if not chunk_path.is_file():
            matches = list((PROCESSED_ROOT / layer).rglob(f"{chunk_id}.md"))
            chunk_path = matches[0] if matches else chunk_path
        if not chunk_path.is_file():
            continue

        try:
            raw = read_text_file(chunk_path)
        except OSError as exc:
            logger.error("Read failed %s: %s", chunk_path, exc)
            continue

        content = strip_yaml_front_matter(raw).strip()
        if not content:
            continue

        # 确保 entry 包含所有需要的元数据字段
        enriched_entry = {
            **entry,
            "layer": layer,
            "source_layer": layer,  # 冗余存储
            "source_file": str(chunk_path.relative_to(KNOWLEDGE_BASE_ROOT)).replace("\\", "/"),
        }
        
        meta = chroma_metadata(enriched_entry)
        meta["source"] = chunk_path.name
        meta["source_path"] = str(chunk_path.relative_to(KNOWLEDGE_BASE_ROOT)).replace("\\", "/")
        
        # 统计
        if entry.get("error_tags"):
            stats["with_error_tags"] += 1
        
        documents.append(Document(page_content=content, metadata=meta))

    logger.info(
        "Loaded %d documents from manifest (error_tags: %d, code/formula detected via resplit)",
        len(documents),
        stats["with_error_tags"]
    )
    return documents


def load_documents_from_processed_layers() -> list[Document]:
    """Fallback: recursively scan all 7 teaching layer directories with enriched metadata."""
    documents: list[Document] = []
    for layer in TEACHING_LAYERS:
        for md_path in iter_processed_markdown_recursive(layer):
            try:
                raw = read_text_file(md_path)
            except OSError as exc:
                logger.error("Read failed %s: %s", md_path, exc)
                continue

            content = strip_yaml_front_matter(raw).strip()
            if not content:
                continue
            
            # 尝试从内容推断章节标题
            import re
            chapter_title = ""
            chapter_match = re.search(r"^#{1,4}\s+(.+)$", content, re.MULTILINE)
            if chapter_match:
                chapter_title = chapter_match.group(1).strip()
            
            # 尝试从内容推断难度
            difficulty = 3
            if "**困难**" in content or "难度：高" in content:
                difficulty = 5
            elif "**中等**" in content or "难度：中" in content:
                difficulty = 3
            elif "**简单**" in content or "难度：低" in content:
                difficulty = 1
            
            # 检测内容类型
            content_types = []
            if "```" in content:
                content_types.append("code")
            if "$" in content or r"\[" in content:
                content_types.append("formula")
            if "**题目**" in content or "**例题**" in content:
                content_types.append("exercise")
            
            # 构建增强元数据
            entry = {
                "chunk_id": md_path.stem,
                "title": md_path.stem,
                "layer": layer,
                "source_layer": layer,  # 冗余存储便于过滤
                "concepts": [],
                "difficulty": difficulty,
                "error_tags": [],  # 空数组，后续可通过人工标注或模型推断填充
                "source_file": str(md_path.relative_to(KNOWLEDGE_BASE_ROOT)).replace("\\", "/"),
                "resource_type": "概念",
                "chapter_title": chapter_title,
                "content_types": ",".join(content_types) if content_types else "",
            }
            meta = chroma_metadata(entry)
            meta["source"] = md_path.name
            meta["source_path"] = str(md_path.relative_to(KNOWLEDGE_BASE_ROOT)).replace("\\", "/")
            
            documents.append(Document(page_content=content, metadata=meta))

    logger.info("Loaded %d documents from 7 processed layers", len(documents))
    return documents


def structure_aware_split_document(
    doc: Document,
    target_size: int = DEFAULT_CHUNK_SIZE,
    overlap_size: int = DEFAULT_CHUNK_OVERLAP,
) -> list[Document]:
    """
    结构感知的智能分块：保护代码块、LaTeX公式、题目完整性。
    
    策略：
    1. 先分析文档结构（章节、公式、代码、题目）
    2. 构建保护区域列表（代码块、公式不能从中切断）
    3. 在保护区域外寻找最佳切分点
    4. 如果某保护块过大，作为独立chunk保留
    """
    text = doc.page_content
    if len(text) <= target_size:
        return [doc]
    
    # 检测文档结构
    structure = detect_content_structure(text)
    
    # 构建保护区域：代码块和公式不能被切断
    protected_ranges: list[tuple[int, int]] = []
    for cb in structure["code_blocks"]:
        protected_ranges.append((cb["start"], cb["end"]))
    for f in structure["formulas"]:
        if f["type"] == "display":
            protected_ranges.append((f["start"], f["end"]))
    for table in structure["tables"]:
        protected_ranges.append((table["start"], table["end"]))
    
    # 保护题目完整性
    for ex in structure["exercises"]:
        protected_ranges.append((ex["start"], ex["end"]))
    
    # 合并重叠的保护区域
    protected_ranges.sort(key=lambda x: x[0])
    merged_ranges: list[tuple[int, int]] = []
    for start, end in protected_ranges:
        if merged_ranges and start <= merged_ranges[-1][1]:
            merged_ranges[-1] = (merged_ranges[-1][0], max(merged_ranges[-1][1], end))
        else:
            merged_ranges.append((start, end))
    
    # 寻找安全切分位置
    split_positions = find_safe_split_positions(text, target_size, overlap_size, merged_ranges)
    
    # 生成子文档
    sub_docs: list[Document] = []
    for i, (start, end) in enumerate(zip(split_positions[:-1], split_positions[1:])):
        chunk_text = text[start:end].strip()
        if not chunk_text:
            continue
        
        # 增强元数据
        meta = {**doc.metadata}
        meta["chunk_index"] = i
        meta["chunk_start"] = start
        meta["chunk_end"] = end
        
        # 识别该chunk包含的结构类型
        chunk_types = []
        for cb in structure["code_blocks"]:
            if start <= cb["start"] < end or start < cb["end"] <= end:
                chunk_types.append("code")
                break
        for f in structure["formulas"]:
            if start <= f["start"] < end or start < f["end"] <= end:
                chunk_types.append("formula")
                break
        for ex in structure["exercises"]:
            if start <= ex["start"] < end or start < ex["end"] <= end:
                chunk_types.append("exercise")
                break
        for table in structure["tables"]:
            if start <= table["start"] < end or start < table["end"] <= end:
                chunk_types.append("table")
                break
        
        if chunk_types:
            meta["content_types"] = ",".join(chunk_types)
        
        # 识别所属章节
        for ch in structure["chapters"]:
            if ch["start"] <= start < ch["end"] or start <= ch["start"] < end:
                meta["chapter_title"] = ch["title"]
                meta["chapter_level"] = ch.get("level", 1)
                break
        
        sub_docs.append(Document(page_content=chunk_text, metadata=meta))
    
    return sub_docs


def structure_aware_split_documents(
    documents: list[Document],
    target_size: int = DEFAULT_CHUNK_SIZE,
    overlap_size: int = DEFAULT_CHUNK_OVERLAP,
) -> list[Document]:
    """批量结构感知的智能分块。"""
    output: list[Document] = []
    for doc in documents:
        subs = structure_aware_split_document(doc, target_size, overlap_size)
        output.extend(subs)
    return output


def maybe_resplit_documents(documents: list[Document]) -> list[Document]:
    """
    对过大的文档进行结构感知重分块。

    统一使用项目自有的结构感知分块，保护代码、公式和题目边界，
    并避免为一个简单的纯文本分块操作引入额外的 URL 加载依赖。
    """
    output: list[Document] = []
    
    for doc in documents:
        text = doc.page_content
        if len(text) <= DEFAULT_CHUNK_SIZE:
            # 小文档直接保留，但确保元数据完整
            output.append(doc)
            continue
        
        subs = structure_aware_split_document(
            doc,
            DEFAULT_CHUNK_SIZE,
            DEFAULT_CHUNK_OVERLAP,
        )
        
        # 确保每个子块都有完整的元数据
        for sub in subs:
            # 继承父文档的 source_layer
            if "layer" in doc.metadata and "source_layer" not in sub.metadata:
                sub.metadata["source_layer"] = doc.metadata["layer"]
            # 传播 error_tags（如果父文档有）
            if "error_tags" in doc.metadata:
                sub.metadata["error_tags"] = doc.metadata["error_tags"]
        
        output.extend(subs)
    
    logger.info(
        "Resplit %d documents into %d chunks (protected: %d)",
        len(documents),
        len(output),
        sum(1 for d in documents if len(d.page_content) > DEFAULT_CHUNK_SIZE and 
            detect_content_structure(d.page_content)["code_blocks"])
    )
    return output


def group_documents_by_chroma_layer(documents: list[Document]) -> dict[str, list[Document]]:
    grouped: dict[str, list[Document]] = defaultdict(list)
    for doc in documents:
        grouped[str(doc.metadata.get("chroma_layer", "application_layer"))].append(doc)
    return dict(grouped)


def collection_name_for_layer(layer_name: str) -> str:
    """Always use the global collection name (Java Langchain4j contract)."""
    _ = layer_name
    return GLOBAL_COLLECTION_NAME


def chroma_base_url() -> str:
    return f"http://{CHROMA_HOST}:{CHROMA_PORT}"


def create_chroma_http_client():
    """ChromaDB HttpClient — same endpoint as Java ChromaEmbeddingStore."""

    return chromadb.HttpClient(
        host=CHROMA_HOST,
        port=CHROMA_PORT,
        tenant=CHROMA_TENANT,
        database=CHROMA_DATABASE,
    )


def chroma_vectorstore_kwargs(*, collection_name: str | None = None) -> dict[str, Any]:
    """Keyword args for ``Chroma.from_documents`` / ``Chroma()`` HTTP mode."""
    return {
        "client": create_chroma_http_client(),
        "collection_name": collection_name or GLOBAL_COLLECTION_NAME,
    }


def verify_chroma_server() -> None:
    """Fail fast if the standalone Chroma HTTP service is not reachable."""
    import httpx

    url = f"{chroma_base_url()}/api/v2/heartbeat"
    try:
        resp = httpx.get(url, timeout=10.0)
        if resp.status_code == 502:
            raise ConnectionError(
                f"Port {CHROMA_PORT} returned HTTP 502 — not a healthy Chroma server. "
                "Another program or proxy may be using this port. "
                f"Stop it, then run: .\\scripts\\start_chroma.ps1"
            )
        resp.raise_for_status()
        logger.info(
            "Chroma HTTP OK at %s (tenant=%s, database=%s, heartbeat=%s)",
            chroma_base_url(),
            CHROMA_TENANT,
            CHROMA_DATABASE,
            resp.text.strip(),
        )
    except ConnectionError:
        raise
    except httpx.HTTPError as exc:
        raise ConnectionError(
            f"Cannot reach Chroma at {chroma_base_url()} ({exc}). "
            "Start the server in a separate terminal: .\\scripts\\start_chroma.ps1"
        ) from exc


def reset_collection(collection_name: str) -> None:
    """Drop collection on the remote Chroma server before full re-ingestion."""
    client = create_chroma_http_client()
    try:
        client.delete_collection(collection_name)
        logger.info("Removed remote collection: %s", collection_name)
    except Exception as exc:
        logger.debug("Collection reset skipped (%s): %s", collection_name, exc)


def persist_layer_to_chroma(
    layer_name: str,
    documents: list[Document],
    embeddings: Embeddings,
    *,
    reset_collection_first: bool = False,
) -> Chroma | None:
    if not documents:
        logger.warning("No documents for %s, skip.", layer_name)
        return None

    collection_name = GLOBAL_COLLECTION_NAME
    if reset_collection_first:
        reset_collection(collection_name)
    vectorstore = Chroma.from_documents(
        documents=documents,
        embedding=embeddings,
        **chroma_vectorstore_kwargs(collection_name=collection_name),
    )
    logger.info(
        "Ingested chroma_layer=%s -> %s@%s (%d chunks)",
        layer_name,
        collection_name,
        chroma_base_url(),
        len(documents),
    )
    return vectorstore


def persist_global_knowledge_collection(
    documents: list[Document],
    embeddings: Embeddings,
) -> Chroma | None:
    """Write all layers into visionary_global_knowledge on the Chroma HTTP service."""
    if not documents:
        logger.error("No documents to persist into %s", GLOBAL_COLLECTION_NAME)
        return None

    reset_collection(GLOBAL_COLLECTION_NAME)
    vectorstore = Chroma.from_documents(
        documents=documents,
        embedding=embeddings,
        **chroma_vectorstore_kwargs(),
    )
    try:
        count = vectorstore._collection.count()
    except Exception as exc:
        logger.warning("Post-ingest count check failed: %s", exc)
        count = len(documents)
    grouped = group_documents_by_chroma_layer(documents)
    logger.info(
        "Global ingestion -> %s@%s | stored=%d | uploaded=%d | by chroma_layer=%s",
        GLOBAL_COLLECTION_NAME,
        chroma_base_url(),
        count,
        len(documents),
        {k: len(v) for k, v in grouped.items()},
    )
    return vectorstore


def process_and_store() -> dict[str, int]:
    verify_chroma_server()

    documents = load_documents_from_manifest()
    layer_documents = load_documents_from_processed_layers()
    if layer_documents:
        seen = {
            str(doc.metadata.get("source_path") or doc.metadata.get("source") or "")
            for doc in documents
        }
        for doc in layer_documents:
            key = str(doc.metadata.get("source_path") or doc.metadata.get("source") or "")
            if key not in seen:
                documents.append(doc)
                seen.add(key)

    if not documents:
        logger.error("No documents found under knowledge_base/processed or knowledge_base/curated.")
        return {}

    documents = maybe_resplit_documents(documents)
    embeddings = create_embeddings()

    persist_global_knowledge_collection(documents, embeddings)

    grouped = group_documents_by_chroma_layer(documents)
    stats = {
        layer_name: len(grouped.get(layer_name, []))
        for layer_name in ("application_layer", "algorithm_layer", "math_layer")
    }

    logger.info(
        "Ingestion complete (collection=%s @ %s): %s (total=%d)",
        GLOBAL_COLLECTION_NAME,
        chroma_base_url(),
        stats,
        sum(stats.values()),
    )
    return stats


if __name__ == "__main__":
    process_and_store()
