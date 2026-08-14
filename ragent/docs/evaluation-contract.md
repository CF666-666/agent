# Ragent 评测数据契约

评测数据在调用服务前必须经过 `scripts/eval/evaluation_contract.py` 校验。该契约是后续 240 条单轮集、20 组多轮集和 60 条 RAGAS 分层集的唯一字段口径。

## 校验命令

```powershell
python scripts/eval/evaluation_contract.py scripts/eval/datasets/industrial_eval_v2.jsonl
python scripts/eval/evaluation_contract.py path/to/conversation_eval.jsonl --kind conversation
python scripts/eval/evaluation_contract.py --tuning path/to/tuning.jsonl --frozen path/to/frozen.jsonl
```

## 单轮样本

所有单轮样本必填：`id`、`query`、`golden_answer`、`scene`、`expected_channels`、`golden_source_ids`、`provenance.source_file`、`provenance.source_record_id`。

新样本还应填写：

- `schema_version: 1`、`case_type: "single_turn"`；
- `split: "tuning" | "frozen"`；
- `ragas_group: "text" | "noise" | "image" | "relation"`（纳入 RAGAS 时）；
- `business_tags`（如故障诊断、操作规程、维护保养、安全规范）。

`scene` 取值为 `fact`、`colloquial`、`image`、`relation`。`image` 必须提供 `golden_image_paths`；`relation` 必须提供 `golden_hyperedge_ids`。

R3 单轮噪声样本额外必填 `canonical_query`、`noise_type` 与 `mutation_notes`。`noise_type` 仅允许 `typo_homophone`、`alias_synonym`、`unit_format`、`ellipsis_word_order`；样本必须使用 `scene: "colloquial"` 与 `ragas_group: "noise"`，且变体不得与规范问题相同。当前 40 条数据由显式逐条变体构成，不使用通用口语前缀模板，也不声明人工评价。

现有 v2 基线未填写的新增字段按兼容模式处理为 `legacy`，以保证历史报告仍可复现；新数据不得继续使用 `legacy`。

## 调优集与冻结集隔离

新的 `tuning`、`frozen` 数据集必须显式声明 `schema_version: 1`、对应的 `case_type` 和 `split`。发布或调优前，使用上述 `--tuning/--frozen` 命令同时校验两份数据；历史 `legacy` 集不得参与该校验。

校验器会拒绝两个集合共享任一可评分证据：`golden_source_ids`、`provenance.source_file + source_record_id`、图像场景的 `golden_image_paths`，以及关系场景的 `golden_hyperedge_ids`。因此同一来源知识、图像素材或超边不会一边用于调参、一边用于宣称最终效果。

## 多轮样本

多轮样本使用 `case_type: "conversation"` 和 `scene: "colloquial"`，以 `turns` 保存按顺序排列的 user 输入。数据文件不预写 assistant 回复：Runner 必须完整消费每个历史 user 轮的真实 SSE 回答，由被测系统将其持久化到同一个 `conversationId`，再发送下一轮。`target_turn_index` 必须指向最后一个 user turn，辅助轮不参与质量指标。

R3 会话还必须声明 `conversation_type: "ellipsis" | "cross_turn_reference"`、`canonical_target_query` 与 `context_notes`。报告应保存每轮真实 query、assistant answer、references、执行状态、耗时及会话 ID，以便核验历史是否真实生效；只有目标轮的 references 可计算 Hit@K、MRR 和来源命中。

## 契约保证

- JSONL 语法、必填字段、枚举值、场景专属证据和重复 ID 会在本地失败；
- 检索 Runner 加载单轮集时强制调用校验；
- 数据集的 SHA-256、运行配置和报告合并规则仍由现有 Runner 管理；
- 每份新检索/RAGAS 报告还会记录运行器、数据契约、运行配置档案、应用配置、Git 提交和 Python 版本的无密钥执行指纹；分批检索报告指纹不一致时拒绝合并。历史 schema v2 报告保持只读，新生成的 schema v3 报告不得与其混合；
- 契约不替代业务真实性审核：素材内容与描述的真实性仍需在数据构建时人工审核；图像授权由素材提供者声明（license 字段仅作记录，不作为契约强制项）；调优/冻结集隔离已由 R0-D 的加载前校验强制执行。

## 受控基线归档

基线必须由 schema v3 的场景分批报告和对应合并报告构成。`scripts/eval/archive_baseline.py` 会重新计算合并结果，拒绝手工改写的汇总，并一并归档原始报告、合并报告、无密钥复跑命令、后端容器镜像 ID 与执行指纹。它不会读取、展示或写入任何 API Key。

示例：

```powershell
python scripts/eval/archive_baseline.py `
  --raw scripts/eval/report/baselines/<run>/raw_fact.json scripts/eval/report/baselines/<run>/raw_colloquial.json scripts/eval/report/baselines/<run>/raw_image.json scripts/eval/report/baselines/<run>/raw_relation.json `
  --merged scripts/eval/report/baselines/<run>/merged.json `
  --out-dir scripts/eval/report/baselines/<run>/archive `
  --backend-image sha256:<immutable-image-id>
```
