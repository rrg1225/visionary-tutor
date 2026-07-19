#!/usr/bin/env python3
"""pptx_kit 单元测试：Normalizer / check_plan / session_loader。"""

from __future__ import annotations

import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path

_ENGINE = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(_ENGINE))

from pptx_kit.content_normalizer import PremiumNormalizer, clamp, clamp_lines, clean_text_line, extract_quiz_stem
from pptx_kit.check_plan import check_and_trim_plan
from pptx_kit.session_loader import load_session_json, try_parse_artifact_json
from pptx_kit.fill_plan_builder import build_fill_plan
from pptx_kit.template_guard import _compute_sha256


class TestClamp(unittest.TestCase):
    def test_clamp_short_text(self) -> None:
        self.assertEqual(clamp("短文本", 40), "短文本")

    def test_clamp_long_text_with_ellipsis(self) -> None:
        long_text = "A" * 50
        result = clamp(long_text, 40, ellipsis=True)
        self.assertEqual(len(result), 40)
        self.assertTrue(result.endswith("…"))

    def test_clamp_lines_max_items(self) -> None:
        lines = [f"line{i}" for i in range(10)]
        result = clamp_lines(lines, 3, 72)
        self.assertEqual(len(result), 3)


class TestPremiumNormalizer(unittest.TestCase):
    def test_handout_from_string(self) -> None:
        n = PremiumNormalizer()
        out = n.clamp({"handout": "第一行\n第二行\n第三行"})
        self.assertEqual(len(out.get("handout", [])), 3)

    def test_handout_from_list(self) -> None:
        n = PremiumNormalizer()
        out = n.clamp({"handout": ["要点一", "要点二"]})
        self.assertEqual(len(out["handout"]), 2)

    def test_topic_default(self) -> None:
        n = PremiumNormalizer()
        out = n.clamp({})
        self.assertTrue(out["topic"])

    def test_quiz_stem_only(self) -> None:
        n = PremiumNormalizer()
        out = n.clamp({
            "quiz": [
                {"question": "什么是卷积？"},
                {"stem": "ReLU 作用？"},
            ]
        })
        self.assertEqual(len(out["quiz"]), 2)
        self.assertIn("卷积", out["quiz"][0])


class TestSessionLoader(unittest.TestCase):
    def test_load_valid_json(self) -> None:
        with tempfile.NamedTemporaryFile(mode="w", suffix=".json", delete=False, encoding="utf-8") as f:
            json.dump({"topic": "测试"}, f)
            path = Path(f.name)
        try:
            data = load_session_json(path)
            self.assertEqual(data["topic"], "测试")
        finally:
            path.unlink(missing_ok=True)

    def test_try_parse_artifact_json_invalid(self) -> None:
        self.assertEqual(try_parse_artifact_json("{bad json"), {})

    def test_try_parse_artifact_json_none(self) -> None:
        self.assertEqual(try_parse_artifact_json(None), {})


class TestFillPlanAndCheckPlan(unittest.TestCase):
    def test_build_fill_plan(self) -> None:
        mapping = {
            "field_mappings": {
                "topic": "{{topic}}",
                "handout": "{{handout}}",
            }
        }
        normalized = {"topic": "CNN", "handout": ["a", "b"]}
        plan = build_fill_plan(normalized, mapping)
        self.assertEqual(plan["{{topic}}"], "CNN")
        self.assertIn("1. a", plan["{{handout}}"])

    def test_check_plan_trims_overflow(self) -> None:
        capacity = {"topic": {"max_chars": 10, "ellipsis": True}}
        field_mappings = {"topic": "{{topic}}"}
        plan = {"{{topic}}": "这是一个非常非常长的主题标题"}
        trimmed = check_and_trim_plan(plan, capacity, field_mappings)
        self.assertLessEqual(len(trimmed["{{topic}}"]), 10)

    def test_check_plan_list_field_keeps_multiple_items(self) -> None:
        capacity = {
            "handout": {"max_items": 6, "max_chars": 72, "ellipsis": True},
        }
        field_mappings = {"handout": "{{handout}}"}
        long_items = [f"要点{i}：" + "X" * 60 for i in range(6)]
        plan = {"{{handout}}": build_fill_plan({"handout": long_items}, {"field_mappings": field_mappings})["{{handout}}"]}
        trimmed = check_and_trim_plan(plan, capacity, field_mappings)
        lines = [ln for ln in trimmed["{{handout}}"].split("\n") if ln.strip()]
        self.assertGreaterEqual(len(lines), 3)
        for line in lines:
            self.assertLessEqual(len(line.replace("…", "")), 76)


class TestTemplateGuard(unittest.TestCase):
    def test_text_metadata_hash_is_stable_across_platform_newlines(self) -> None:
        with tempfile.NamedTemporaryFile(delete=False) as f:
            f.write(b'{"shape_id":"s01"}\r\n')
            path = Path(f.name)
        try:
            expected = hashlib.sha256(b'{"shape_id":"s01"}\n').hexdigest()
            self.assertEqual(_compute_sha256(path, normalize_newlines=True), expected)
        finally:
            path.unlink(missing_ok=True)


class TestMarkdownAndQuizHelpers(unittest.TestCase):
    def test_strip_markdown_preserves_underscores(self) -> None:
        self.assertIn("_", clean_text_line("visionary_global_knowledge RAG"))

    def test_clean_text_line_removes_bold(self) -> None:
        self.assertEqual(clean_text_line("**卷积层**"), "卷积层")

    def test_extract_quiz_stem_from_dict(self) -> None:
        self.assertEqual(extract_quiz_stem({"question": "什么是 CNN？"}), "什么是 CNN？")

    def test_extract_quiz_stem_from_string(self) -> None:
        self.assertEqual(extract_quiz_stem("直接题干"), "直接题干")


if __name__ == "__main__":
    unittest.main()
