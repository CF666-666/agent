import json
import sys
import unittest
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
ROOT = SCRIPT_DIR.parents[1]
sys.path.insert(0, str(SCRIPT_DIR))

import rewrite_audit  # noqa: E402


class ClassifyRewriteTest(unittest.TestCase):

    def test_correct_completion_covers_canonical_entity(self):
        result = rewrite_audit.classify_rewrite(
            "凝汽器真空度骤降应排查哪些环节", "凝汽器真空度骤降应排查哪些环节")
        self.assertEqual("correct_completion", result["classification"])
        self.assertIn("凝汽器", result["covered_entities"])

    def test_ineffective_reference_word_residual(self):
        result = rewrite_audit.classify_rewrite(
            "它应该排查哪些环节", "凝汽器真空度骤降应排查哪些环节")
        self.assertEqual("ineffective_rewrite", result["classification"])
        self.assertEqual("reference_word_residual", result["reason"])

    def test_wrong_entity_injection(self):
        result = rewrite_audit.classify_rewrite(
            "汽轮机停用一周应采取何种保养措施", "锅炉停用一周以上应采取何种保养措施")
        self.assertEqual("wrong_entity_injection", result["classification"])
        self.assertIn("汽轮机", result["injected_entities"])

    def test_ineffective_no_golden_entity_covered(self):
        result = rewrite_audit.classify_rewrite(
            "应该排查哪些环节", "凝汽器真空度骤降应排查哪些环节")
        self.assertEqual("ineffective_rewrite", result["classification"])
        self.assertEqual("no_golden_entity_covered", result["reason"])

    def test_should_clarify_but_didnt(self):
        result = rewrite_audit.classify_rewrite(
            "锅炉停用保养措施", "锅炉停用一周以上应采取何种保养措施",
            clarification_expected=True)
        self.assertEqual("should_clarify_but_didnt", result["classification"])

    def test_clarification_query_not_flagged(self):
        result = rewrite_audit.classify_rewrite(
            "请问你指的是哪台设备", "锅炉停用一周以上应采取何种保养措施",
            clarification_expected=True)
        self.assertNotEqual("should_clarify_but_didnt", result["classification"])

    def test_reference_words_do_not_match_should(self):
        self.assertFalse(rewrite_audit.has_reference_word("锅炉停用应该采取什么保养措施"))
        self.assertTrue(rewrite_audit.has_reference_word("这个部件多久换"))

    def test_synonym_normalized_to_canonical_entity(self):
        # 主变 = 变压器，应视为正确补全而非错误注入
        result = rewrite_audit.classify_rewrite(
            "主变油温95度往上走的原因",
            "变压器运行中油温异常升高至95℃以上，可能原因有哪些？",
            equivalence=rewrite_audit.SYNONYM_EQUIVALENCE,
            source_entities=rewrite_audit.extract_entities("主变油温95度往上走"))
        self.assertEqual("correct_completion", result["classification"])
        self.assertIn("变压器", result["covered_entities"])

    def test_hyponym_covers_hypernym(self):
        # 水冷壁/过热器 是「受热面」的部件枚举（数据集锚定），应视为覆盖而非等价
        result = rewrite_audit.classify_rewrite(
            "锅炉水冷壁和过热器吹灰的周期和参数设定",
            "锅炉受热面吹灰的周期和关键参数是什么？",
            equivalence=rewrite_audit.SYNONYM_EQUIVALENCE,
            hypernym_coverage=rewrite_audit.HYPERNYM_COVERAGE,
            source_entities=rewrite_audit.extract_entities("锅炉水冷壁和过热器多久吹一次灰"))
        self.assertEqual("correct_completion", result["classification"])
        self.assertIn("水冷壁", result["covered_entities"])

    def test_hyponym_not_in_synonym_equivalence(self):
        # P1-1 修复核心：上下位（水冷壁/过热器）不应作为同义/简称归一化
        # （那会掩盖「范围缩小」错误），而应通过独立的 HYPERNYM_COVERAGE 覆盖表处理。
        self.assertNotIn("水冷壁", rewrite_audit.SYNONYM_EQUIVALENCE)
        self.assertNotIn("过热器", rewrite_audit.SYNONYM_EQUIVALENCE)
        self.assertIn("水冷壁", rewrite_audit.HYPERNYM_COVERAGE["受热面"])
        self.assertIn("过热器", rewrite_audit.HYPERNYM_COVERAGE["受热面"])

    def test_source_entity_excluded_from_injection(self):
        # 「风机」来自原始 query，属于改写不足而非幻觉注入
        result = rewrite_audit.classify_rewrite(
            "风机电机老跳是电气问题还是机械问题",
            "冷却塔电机频繁跳闸，可能是什么问题？",
            equivalence=rewrite_audit.SYNONYM_EQUIVALENCE,
            source_entities=rewrite_audit.extract_entities("这个风机电机老跳"))
        self.assertEqual("ineffective_rewrite", result["classification"])
        self.assertEqual([], result["injected_entities"])

    def test_hallucinated_entity_still_injected(self):
        # 「汽轮机」不在 query/history，也不在 canonical → 真注入
        result = rewrite_audit.classify_rewrite(
            "汽轮机停用一周应采取何种保养措施",
            "锅炉停用一周以上应采取何种保养措施",
            equivalence=rewrite_audit.SYNONYM_EQUIVALENCE,
            source_entities=rewrite_audit.extract_entities("这台锅炉计划停用十天"))
        self.assertEqual("wrong_entity_injection", result["classification"])
        self.assertIn("汽轮机", result["injected_entities"])

    def test_normalize_text_applies_each_mapping_once(self):
        mappings = [("主变", "变压器"), ("汽机", "汽轮机")]
        self.assertEqual("变压器汽轮机故障", rewrite_audit.normalize_text("主变汽机故障", mappings))

    def test_normalize_text_noop_without_mappings(self):
        self.assertEqual("原文不变", rewrite_audit.normalize_text("原文不变", []))

    def test_apply_mapping_already_target_protection(self):
        # source 是 target 的前缀时，已为 target 的位置不重复替换（复现后端 alreadyTarget）
        self.assertEqual("保险单", rewrite_audit.apply_mapping("保险单", "保", "保险"))

    def test_apply_mapping_single_pass_no_reprocess(self):
        # 单遍扫描：替换产生的新 source 不再被处理（复现后端单遍语义，而非循环至稳定）
        self.assertEqual("变压器故障", rewrite_audit.apply_mapping("主变故障", "主变", "变压器"))

    def test_dedupe_overlapping_keeps_longest(self):
        self.assertEqual(["热轧机"], rewrite_audit.dedupe_overlapping(["轧机", "热轧机"]))


class DomainEntityCoverageTest(unittest.TestCase):

    def _canonicals(self, path, key):
        with path.open(encoding="utf-8") as handle:
            return [json.loads(line)[key] for line in handle if line.strip()]

    def test_every_noise_canonical_has_domain_entity(self):
        path = SCRIPT_DIR / "datasets/industrial_noise_r3.jsonl"
        for canonical in self._canonicals(path, "canonical_query"):
            self.assertTrue(
                rewrite_audit.extract_entities(canonical),
                f"noise canonical missing domain entity: {canonical}")

    def test_every_conversation_canonical_has_domain_entity(self):
        path = SCRIPT_DIR / "datasets/industrial_conversation_r3.jsonl"
        for canonical in self._canonicals(path, "canonical_target_query"):
            self.assertTrue(
                rewrite_audit.extract_entities(canonical),
                f"conversation canonical missing domain entity: {canonical}")


class ParseRewriteJsonTest(unittest.TestCase):

    def test_parses_plain_json(self):
        parsed = rewrite_audit.parse_rewrite_json(
            '{"rewrite": "12306系统的架构", "sub_questions": ["12306系统的架构"]}')
        self.assertEqual("12306系统的架构", parsed["rewrite"])
        self.assertEqual(["12306系统的架构"], parsed["sub_questions"])

    def test_parses_markdown_fence(self):
        parsed = rewrite_audit.parse_rewrite_json(
            '```json\n{"rewrite": "高炉冷却壁漏水诊断", "sub_questions": ["高炉冷却壁漏水诊断"]}\n```')
        self.assertEqual("高炉冷却壁漏水诊断", parsed["rewrite"])

    def test_missing_sub_questions_falls_back_to_rewrite(self):
        parsed = rewrite_audit.parse_rewrite_json('{"rewrite": "锅炉停炉操作"}')
        self.assertEqual(["锅炉停炉操作"], parsed["sub_questions"])

    def test_blank_rewrite_returns_none(self):
        self.assertIsNone(rewrite_audit.parse_rewrite_json('{"rewrite": ""}'))
        self.assertIsNone(rewrite_audit.parse_rewrite_json("not json"))
        self.assertIsNone(rewrite_audit.parse_rewrite_json(""))


class BuildMessagesTest(unittest.TestCase):

    def test_history_truncation_matches_backend_skip_semantics(self):
        # 后端 buildRewriteRequest：filter(USER/ASSISTANT) 后 skip(原始 size - 4)。
        # 原始 size=6 → skip=2，过滤后 [u1,a1,u2,a2,u3] 去掉前 2 → [u2,a2,u3]。
        history = [
            {"role": "system", "content": "summary"},
            {"role": "user", "content": "u1"},
            {"role": "assistant", "content": "a1"},
            {"role": "user", "content": "u2"},
            {"role": "assistant", "content": "a2"},
            {"role": "user", "content": "u3"},
        ]
        messages = rewrite_audit.build_messages("SYS", "question", history)
        self.assertEqual("SYS", messages[0]["content"])
        contents = [m["content"] for m in messages[1:]]
        self.assertEqual(["u2", "a2", "u3", "question"], contents)


class SummarizeTest(unittest.TestCase):

    def test_summary_counts_classifications(self):
        cases = [
            {"classification": "correct_completion", "reproduction_ok": True},
            {"classification": "correct_completion", "reproduction_ok": True},
            {"classification": "wrong_entity_injection", "reproduction_ok": True},
            {"classification": "ineffective_rewrite", "reproduction_ok": False},
        ]
        summary = rewrite_audit.summarize(cases)
        self.assertEqual(4, summary["total"])
        self.assertEqual(2, summary["classification_counts"]["correct_completion"])
        self.assertEqual(1, summary["classification_counts"]["wrong_entity_injection"])
        self.assertEqual(1, summary["classification_counts"]["ineffective_rewrite"])
        self.assertEqual(0, summary["classification_counts"]["should_clarify_but_didnt"])
        self.assertEqual(3, summary["reproduction_ok"])
        self.assertEqual(0, summary["should_clarify_cases"])


if __name__ == "__main__":
    unittest.main()
