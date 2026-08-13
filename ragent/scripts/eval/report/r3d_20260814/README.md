# R3-D 查询改写错误分析 — 结论文档

> 闭环：R3-D（错误改写分析）
> 日期：2026-08-14
> 状态：✅ 完成
> 方法：离线复现审计（与后端同 prompt / 模型 / 采样参数，不改后端）

---

## 1. 方法

改写结果后端未暴露到 SSE，故采用**离线复现**：`rewrite_audit.py` 用与后端完全相同的
`prompt/user-question-rewrite.st` + `deepseek-ai/DeepSeek-V3.2`（SiliconFlow）+ 采样参数
（temperature 0.1 / top_p 0.3 / max_tokens 512），对 noise 单轮与 conversation 多轮目标轮
重放改写，再按确定性规则分类器归类。

分类器（纯函数、无 LLM，18 项单测覆盖）判定四类：

| 类别 | 判定信号 |
|---|---|
| correct_completion | 改写覆盖 canonical 中的规范实体，无错误实体 |
| ineffective_rewrite | 残留指代词，或未覆盖任何 golden 实体 |
| wrong_entity_injection | 引入 canonical 之外、且非来源（query/history）已有的实体（幻觉） |
| should_clarify_but_didnt | 历史歧义却直接猜测而非澄清（本数据集恒 0，见 §4） |

为消除同义/简称误判，注入判定前先做实体等价归一化（`主变→变压器`、`主减速箱→齿轮箱` 等
**同义/简称**），并把来自原始 query/history 的实体排除出「注入」集合（保留来源别名属改写
不足，非幻觉注入）。**上下位**（`水冷壁/过热器` 是 `受热面` 的部件枚举）与同义分离，仅在
「覆盖判定」通过独立的 `HYPERNYM_COVERAGE` 表生效，避免把部件替换误判为等价实体。

复现链路与后端完全对齐：前置 `normalize`（术语映射，评测环境 `t_query_term_mapping`
enabled=1 共 0 条，经 psql 核验，为 no-op）、历史截断（filter USER/ASSISTANT 后按原始
size-4 skip，忠实复现 `buildRewriteRequest`）、prompt/model/采样参数一致。

## 2. 结果

样本：noise 40 条（tuning/frozen 各 20）+ conversation 20 组目标轮（tuning/frozen 各 10）。
改写复现成功率 60/60（无 LLM 解析失败）。

| 数据集 | correct | ineffective | wrong_injection | should_clarify |
|---|---:|---:|---:|---:|
| noise 单轮（40） | 24 | 16 | 0 | 0 |
| conversation 多轮（20） | 20 | 0 | 0 | 0 |

### 2.1 noise 单轮：改写短板集中于「单轮省略」与「错字未纠正」

| noise_type | correct | ineffective |
|---|---:|---:|
| typo_homophone（错字/同音） | 7 | 3 |
| alias_synonym（同义/简称） | 8 | 2 |
| unit_format（单位/格式） | 8 | 2 |
| ellipsis_word_order（单轮省略/语序） | 1 | 9 |

- **单轮省略是最大短板**：10 例仅 1 例补全。无历史上下文时，被省略的设备（如「烘炉送风」
  缺「高炉」、「兑铁前炉前确认」缺「转炉」）在信息论上无法可靠恢复，模型选择保留省略而非
  猜测。**客观事实是「未补全」**；「需澄清机制」是可验证的改进方向之一（间接证据），本报告
  不作为已验证结论。
- **错字未纠正**（气轮机/脱流塔/热扎机）3 例：改写模型未做形近/音近纠错，导致向量检索仍
  命中失败。这解释了 R3-C noise 集 rewrite-on 仅 45% Hit@1（24/40 正确改写的部分 + 16/40
  无效改写全部不命中）。

### 2.2 conversation 多轮：有历史上下文时指代/省略消解 20/20 正确

多轮目标轮（「它的间隙怎么检查和调整？」「这个部件多久换？」等）在有历史轮次的条件下，
改写全部正确补全。这解释了 R3-C conversation 集 rewrite-on 命中率显著高于 off。

> 观察（限制性）：R3-C 中 `frozen-008`（锅炉停用保养）on 检索回退，但本审计复现显示其改写
> 本身是「正确补全」。离线复现与真实后端（在线 LLM 调用 + rerank/融合排序）并非同一路径，
> 该回退更可能来自检索/融合排序层面，本报告不作强因果断言。

## 3. 错误实体注入为 0 的含义（限制性结论）

在当前数据集上，deepseek-v3.2 未产生「凭空引入 unrelated 实体」的幻觉注入。这是**当前样本
上的观察**，不等于系统永不注入；数据集规模（60 例）与覆盖面有限，不能外推为「改写零幻觉」。

## 4. 应澄清未澄清为 0 的说明

当前 frozen/tuning 数据集每个 case 历史信息充分（唯一 golden），不存在「历史有歧义应澄清」
的自然样本；单轮 noise 的 ellipsis_word_order 类是「可接受的改写不足」，数据契约未将其标注
为「应澄清」。故该类如实记为 0，不虚构样本。§2.1 的单轮省略失败本质上是澄清机制缺失的
间接证据，留待后续若产品需要「拍图/口语澄清」能力时单独建集验证。

## 5. 独立复审问题与修复记录

首轮独立复审判定「否」，以下问题已全部修复，复核通过：

| 级别 | 问题 | 修复 |
|---|---|---|
| P0 | 复现遗漏了后端 `normalize` 前置步骤 | 新增 `apply_mapping`（精确复现后端单遍扫描 + alreadyTarget 保护）+ `normalize_text`（逐 mapping 单遍）+ `--term-mappings` 链路；经 psql 核验评测环境 `t_query_term_mapping` enabled=1 共 0 条（normalize 为 no-op），并在报告 `rewrite_reproducer.normalize_note` 披露该假设 |
| P0 | 历史截断与后端不一致（后端 skip 用原始 size） | `build_messages` 改为忠实复现后端：filter 后按 `max(0, 原始 size-4)` skip，测试锁定该语义 |
| P1 | 上下位映射（水冷壁/过热器→受热面）会掩盖「范围缩小」错误 | 拆分 `SYNONYM_EQUIVALENCE`（仅同义/简称）与 `HYPERNYM_COVERAGE`（数据集锚定的部件-整体覆盖，仅参与 covered 判定，不参与注入归一化） |
| P1 | source_entities 单轮可能失效 | 披露边界：source_entities 仅含词典内**规范实体**，噪声错字（如「气轮机」）不在词典中不会被误豁免；查询中「规范但错误」实体的情况当前数据不存在。边界记录于本节 |
| P1 | 词典子串重叠（轧机/热轧机） | 新增 `dedupe_overlapping`，covered/injected 输出最长匹配去重 |
| P1 | 测试未覆盖复现一致性契约 | 新增 normalize（含 alreadyTarget 保护、单遍语义）/历史截断/上下位分离/去重测试，单测 18→25 项 |
| P2 | 单轮省略归因过度 | §2.1 将「澄清机制缺失」修正为**客观事实（未补全）+ 可能的改进方向**，不作已验证结论 |
| P2 | 跨报告因果断言 | §2.2 明确「解释性观察」而非因果断言（离线复现 ≠ 真实后端） |

## 6. 复现命令

```bash
cd ragent/scripts/eval
python -m unittest test_rewrite_audit        # 25 项分类器/解析/覆盖测试
python rewrite_audit.py --dry-run            # 报告结构自检（不调 LLM）
# 真实复现（需 SILICONFLOW_API_KEY）
python rewrite_audit.py --out report/r3d_20260814/rewrite_audit.json
```

## 7. 产出文件

- `rewrite_audit.py`：复现 + 确定性分类器 + 报告生成
- `test_rewrite_audit.py`：25 项单测（分类四类、指代检测、同义归一化、上下位覆盖、来源排除、词典覆盖、JSON 解析、历史截断、normalize 单遍+alreadyTarget、去重、汇总）
- `report/r3d_20260814/rewrite_audit.json`：逐 case 改写结果与分类原始报告
