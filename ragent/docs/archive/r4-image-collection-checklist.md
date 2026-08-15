# R4-A 工业图像素材收集清单

> 目标：收集 40 张工业图像素材 → 供 R4-B 生成 100 条评测问题。
> 你只需做两件事：**下载图片 + 填 4 个字段**（image_path / subcategory / image_subject / source_url）。
> `license` 字段由素材提供者声明（Unsplash License/CC0/proprietary 等），仅作记录、不作为进入门槛，可留空（默认按提供者声明已授权）。
> 图片描述（description）由 Qwen-VL 自动生成，无需手写。

---

## 一、分类配额（共 40 张）

| subcategory | 含义 | 张数 |
|---|---|---|
| `engineering_drawing` | 工程图 / P&ID / 原理图 | **20** |
| `scanned_manual` | 扫描手册页 / 设备铭牌 | **10** |
| `site_photo` | 工业现场照片 | **10** |

---

## 二、每张图要填的 5 个字段

| 字段 | 说明 | 示例 |
|---|---|---|
| `image_path` | 图片文件名（放在 `bootstrap/data/images/drawings/` 下） | `drawings/steel_ladle_01.jpg` |
| `subcategory` | 素材类型，三选一 | `engineering_drawing` |
| `image_subject` | 图片主题短语（一句话，用于生成问题） | `连铸机` |
| `source_url` | 来源链接（原始网页 URL） | `https://unsplash.com/photos/xxx` |
| `license` | 授权类型 | `Unsplash License` |

> 不需要你填的：`description`（Qwen-VL 自动生成）、`category`（保留 `industrial_equipment`）、`generated_by`（自动填 `qwen-vl-max`）。

### 完整条目示例（最终由脚本生成，你只需提供上面 5 个字段）

```json
{
  "image_path": "drawings/steel_ladle_01.jpg",
  "description": "（Qwen-VL 自动生成）",
  "category": "industrial_equipment",
  "subcategory": "engineering_drawing",
  "image_subject": "连铸机",
  "source_url": "https://unsplash.com/photos/xxxx",
  "license": "Unsplash License",
  "generated_by": "qwen-vl-max"
}
```

---

## 三、授权类型速查（license 字段可填的值）

| license 值 | 来源 | 是否可商用 |
|---|---|---|
| `Unsplash License` | Unsplash | ✅ 免费商用、无需署名 |
| `CC0` | Wikimedia Commons | ✅ 公有领域，无任何限制 |
| `Public Domain` | Wikimedia Commons / 政府公开资料 | ✅ 公有领域 |
| `CC BY` | Wikimedia Commons | ✅ 可商用，需署名 |
| `CC BY-SA` | Wikimedia Commons | ⚠️ 可商用，但衍生作品需同授权 |
| `proprietary` | 自有/内部素材 | ✅ 素材提供者声明授权（不填具体来源） |

> license 字段由素材提供者声明、仅作记录，不作为进入评测集的门槛；未填时默认按提供者声明已授权。
> **最省力组合**：现场照片 + 铭牌手册页用 Unsplash；工程图/原理图用 Wikimedia Commons 的 CC0 / Public Domain（筛选条件里勾选对应授权）。

---

## 四、找图渠道推荐

| 素材类型 | 推荐渠道 | 说明 |
|---|---|---|
| 工程图 / P&ID / 原理图 | [Wikimedia Commons](https://commons.wikimedia.org) | 搜 "piping and instrumentation diagram"、"engineering drawing"、"P&ID"，筛选 CC0/Public Domain |
| 设备铭牌 / 手册页 | Unsplash 搜 "nameplate"、"pressure gauge"、"control panel" | 现场拍摄的设备铭牌、仪表盘特写 |
| 工业现场照片 | Unsplash 搜 "steel mill"、"factory"、"refinery"、"power plant" | 数量充足，质量高，授权明确 |

---

## 五、收集进度清单（找到一张填一行，最后打勾）

### 5.1 engineering_drawing（工程图/原理图）—— 目标 20 张

| # | image_path | image_subject | source_url | license | ✓ |
|---|---|---|---|---|---|
| 1 | | | | | |
| 2 | | | | | |
| 3 | | | | | |
| 4 | | | | | |
| 5 | | | | | |
| 6 | | | | | |
| 7 | | | | | |
| 8 | | | | | |
| 9 | | | | | |
| 10 | | | | | |
| 11 | | | | | |
| 12 | | | | | |
| 13 | | | | | |
| 14 | | | | | |
| 15 | | | | | |
| 16 | | | | | |
| 17 | | | | | |
| 18 | | | | | |
| 19 | | | | | |
| 20 | | | | | |

### 5.2 scanned_manual（扫描手册页/铭牌）—— 目标 10 张

| # | image_path | image_subject | source_url | license | ✓ |
|---|---|---|---|---|---|
| 1 | | | | | |
| 2 | | | | | |
| 3 | | | | | |
| 4 | | | | | |
| 5 | | | | | |
| 6 | | | | | |
| 7 | | | | | |
| 8 | | | | | |
| 9 | | | | | |
| 10 | | | | | |

### 5.3 site_photo（工业现场照片）—— 目标 10 张

| # | image_path | image_subject | source_url | license | ✓ |
|---|---|---|---|---|---|
| 1 | | | | | |
| 2 | | | | | |
| 3 | | | | | |
| 4 | | | | | |
| 5 | | | | | |
| 6 | | | | | |
| 7 | | | | | |
| 8 | | | | | |
| 9 | | | | | |
| 10 | | | | | |

---

## 六、现有素材复用提示

仓库里已有 12 张图（`bootstrap/data/images/drawings/`），已全部登记并补全 `subcategory`（均为 site_photo）、`image_subject` 与 `license`（3 张 Unsplash License + 9 张 proprietary，均视为提供者声明已授权），可直接复用。

> **注意分层平衡**：`build_image_dataset.py` 的 `validate_distribution` 是精确匹配（subcategory 必须恰好 20/10/10），当前 site_photo 已 12 张、**超目标 2 张**。收齐 40 张时，site_photo 必须**只保留 10 张**——超出的 2 张要么改判为其他 subcategory（若内容符合），要么移出评测集。后续收集重点放在 engineering_drawing（缺 20 张）与 scanned_manual（缺 10 张）。

---

## 七、收集完成后

把填好的清单发给我，我会：
1. 写脚本批量调 Qwen-VL 生成每张图的 `description`
2. 合并成 `descriptions.jsonl`
3. 跑 `image_asset_registry.py` 校验（授权/哈希/去重/达标判定）
4. 跑 `build_image_dataset.py` 生成 100 条评测问题
