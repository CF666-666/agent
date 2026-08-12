# Ragent 评测数据契约

评测数据在调用服务前必须经过 `scripts/eval/evaluation_contract.py` 校验。该契约是后续 240 条单轮集、20 组多轮集和 60 条 RAGAS 分层集的唯一字段口径。

## 校验命令

```powershell
python scripts/eval/evaluation_contract.py scripts/eval/datasets/industrial_eval_v2.jsonl
python scripts/eval/evaluation_contract.py path/to/conversation_eval.jsonl --kind conversation
```

## 单轮样本

所有单轮样本必填：`id`、`query`、`golden_answer`、`scene`、`expected_channels`、`golden_source_ids`、`provenance.source_file`、`provenance.source_record_id`。

新样本还应填写：

- `schema_version: 1`、`case_type: "single_turn"`；
- `split: "tuning" | "frozen"`；
- `ragas_group: "text" | "noise" | "image" | "relation"`（纳入 RAGAS 时）；
- `business_tags`（如故障诊断、操作规程、维护保养、安全规范）。

`scene` 取值为 `fact`、`colloquial`、`image`、`relation`。`image` 必须提供 `golden_image_paths`；`relation` 必须提供 `golden_hyperedge_ids`。

现有 v2 基线未填写的新增字段按兼容模式处理为 `legacy`，以保证历史报告仍可复现；新数据不得继续使用 `legacy`。

## 多轮样本

多轮样本使用 `case_type: "conversation"` 和 `scene: "colloquial"`，以 `turns` 保存按顺序排列的 user/assistant 消息。`target_turn_index` 必须指向最后一个 user turn；该规则使后续 Runner 不会因为会话追加而评测错目标问题。

## 契约保证

- JSONL 语法、必填字段、枚举值、场景专属证据和重复 ID 会在本地失败；
- 检索 Runner 加载单轮集时强制调用校验；
- 数据集的 SHA-256、运行配置和报告合并规则仍由现有 Runner 管理；
- 契约不替代业务真实性审核：图像授权、来源去重和调优/冻结集隔离在 R0-C/R0-D 继续执行。
