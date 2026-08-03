# VibeLex 识别引擎 V1 规格

**产品版本：** V1.0  
**文档修订：** 1.2
**依赖：** [数据库设计](../../reference/database-schema.md)、[归一化规范](../../reference/normalization.md)

---

## 1. 文档目的

定义 VibeLex 如何从真实文本中识别网络梗，包括：

- 候选召回；
- 规则打分；
- 义项消歧；
- 多梗冲突处理；
- 风险策略过滤；
- 输出结构。

V1 为纯**规则驱动**识别：变体、词面规则、正则和上下文规则共同完成召回与打分。语义召回不属于 V1 已实现范围，统一由 V2 的 Elasticsearch 混合召回方案提供。

---

## 2. 输入与输出

### 2.1 输入

```json
{
  "text": "这薯片吃起来特别 treetree 的，真的破防了",
  "language_code": "zh-CN",
  "scene": "comment",
  "options": {
    "min_confidence": 0.6,
    "max_results": 20
  }
}
```

| 字段 | 说明 |
|---|---|
| `text` | 待识别文本 |
| `language_code` | 语言代码，默认 `zh-CN` |
| `scene` | 预留的使用场景标识；V1 接收并记录，但不参与过滤 |
| `options.min_confidence` | 最低置信度阈值 |
| `options.max_results` | 最大返回命中数 |

### 2.2 输出

```json
{
  "matches": [
    {
      "meme_id": 101,
      "meme_code": "MEME_000101",
      "canonical_term": "treetree的",
      "sense_id": 201,
      "sense_no": 1,
      "ambiguous": false,
      "matched_text": "treetree 的",
      "start_offset": 10,
      "end_offset": 21,
      "confidence": 0.91,
      "match_reason": ["normalized_match", "positive_context"],
      "policy": {
        "detect_enabled": true,
        "display_enabled": true,
        "generate_enabled": true,
        "recommend_enabled": true,
        "risk_level": "low"
      }
    }
  ],
  "engine_version": "1.1",
  "processed_at": "2026-07-14T12:00:00.000Z"
}
```

---

## 3. 处理流水线

```text
输入文本
  │
  ▼
[1] 预处理（分句可选、记录原始 offset）
  │
  ▼
[2] 候选召回（变体索引 + 可选正则扫描）
  │
  ▼
[3] 规则评估与打分（按 meme_id + sense_id 聚合）
  │
  ▼
[4] 义项消歧（同一 meme 多 sense 选最优）
  │
  ▼
[5] 多梗冲突处理（overlap 去重与优先级）
  │
  ▼
[6] 策略过滤（status + safety_policies）
  │
  ▼
[7] 阈值裁剪与排序输出
```

各阶段串行执行；阶段 3 内对单条候选的规则可并行评估。

---

## 4. 阶段 1：预处理

```text
1. 保留原始 text 用于 offset 计算；
2. 按句切分（可选，V1 默认整段处理）；
3. 构建带原文 offset 映射的 `base`、`spacing`、`pinyin` 归一化视图（见 normalization-spec.md）；
4. 不修改原始 text 的字符位置。
```

Offset 规则：输出中的 `start_offset` / `end_offset` 基于**原始输入 text** 的 Unicode 码点半开区间 `[start_offset, end_offset)`。归一化视图的匹配位置必须通过其 offset 映射回原文；API 不使用 UTF-8 字节偏移或 UTF-16 索引。

---

## 5. 阶段 2：候选召回

目标：从全库快速缩小候选集，避免对每条规则全表扫描。

### 5.1 变体索引召回（主路径）

```text
1. 分别在原始文本、`base`、`spacing`、`pinyin` 视图中执行多模式匹配；不使用逐子串查询数据库；
2. 为每条变体按 variant_type 选择 profile：普通变体使用 `base`，`spacing_variant` 使用 `spacing`，`pinyin` 使用 `pinyin`；
3. 原始文本匹配结果或归一化视图结果命中后，使用该视图的 offset 映射生成原文 span；
4. 候选携带：meme_id、sense_id（可空）、匹配来源、matched_value、profile、原文 span offsets。
```

性能优化（实现层）：

```text
- 内存 AC 自动机（Aho-Corasick）按 profile 加载全部 active 变体和可索引的词面规则；
- 定时从 DB 增量刷新（建议 5 分钟或发布事件触发）；
- 仅加载 status IN (published, archived) 且 detect_enabled = 1 的词条变体。
```

### 5.1.1 词面规则召回

`exact_match`、`normalized_match`、`pinyin_match` 是候选锚点，不能仅在候选产生后才被执行：

```text
exact_match：将 rule_value 加入原始文本 AC 索引；
normalized_match：将按 base profile 归一化后的 rule_value 加入 base AC 索引；
pinyin_match：将按 pinyin profile 归一化后的 rule_value 加入 pinyin AC 索引；
positive_context / negative_context / entity_exclusion：仅用于候选评分，不独立产生候选。
```

变体命中同样是词面锚点。候选可由变体或上述词面规则任一来源产生，因此发布条件中的“词面匹配规则或变体记录”可被完整执行。

### 5.1.2 自动空格归一化召回

对包含中文字符的 `normalized_match` 词面规则及普通变体，索引会额外建立仅内存使用的 `spacing` 锚点。该锚点删除空格后在同样删除空格的输入视图中匹配，因此 `treetree的` 可以命中 `treetree 的` 或 `tree tree 的`。

该能力不写入 `meme_variants`，不调用 AI，也不改变正式词条或变体的原始写法。纯英文词形不自动启用该视图，以避免将具有不同语义边界的英文短语错误合并。

### 5.2 正则规则召回（补充路径）

```text
对 rule_type = regex_match 且 enabled = 1 的规则，使用预编译正则扫描文本；
命中则将该 meme_id（及 sense_id）加入候选集。
```

V1 建议：正则规则数量 < 500 时全量扫描；超过则按 category 分桶或迁移至 OpenSearch。

### 5.3 语义召回

不属于 V1 实现范围。`semantic_threshold` 曾作为数据库中的预留规则类型出现，但 V1 运行时不读取、不执行该规则，也不依赖向量服务。V2 采用 Elasticsearch kNN 与统一配置阈值实现语义候选召回，详见 [V2 识别与 ES 方案](../v2.0/recognition-elasticsearch.md)。

---

## 6. 阶段 3：规则评估与打分

对每个候选 `(meme_id, sense_id, span)` 加载适用规则。词条级召回产生的 `sense_id` 为空时，必须先展开该词条全部 `active` 义项，再分别计算得分：

```text
sense_id 非空：加载该 sense 的规则 + sense_id 为空的词条级规则；
sense_id 为空且词条存在 active 义项：为每个 active 义项建立候选，加载词条级规则 + 对应义项级规则；
sense_id 为空且词条没有 active 义项：保留词条级候选，最终 sense_id 返回 null。
```

### 6.1 规则类型与计分

| rule_type | 行为 | 默认 weight |
|---|---|---|
| `exact_match` | 原始 span 与 rule_value 完全相等 | +1.2 |
| `normalized_match` | 对应 profile 归一化后相等 | +1.2 |
| `regex_match` | 正则命中 span | +1.2 |
| `pinyin_match` | pinyin profile 归一化后相等 | +1.2 |
| `positive_context` | 窗口内包含关键词（rule_config.window，默认 20 字） | +0.3 |
| `negative_context` | 窗口内包含关键词 | -0.5 |
| `entity_exclusion` | 窗口内出现排除实体/字面义信号 | -1.0（可否决） |

### 6.2 综合得分公式

```text
base_score = Σ (rule_weight × rule_hit)

其中：
- rule_hit = 1（命中）或 0（未命中）；
- negative_context、entity_exclusion 的 weight 可为负；
- 若 entity_exclusion 命中且 |weight| >= 1.0，该候选直接否决（score = 0）。
- 由变体或词面规则召回的候选，召回来源本身计为一条对应的词面命中；未显式配置匹配规则的变体也能获得该基础证据。
- 同一候选的同一种词面证据只计一次：召回来源与等价显式规则不得重复累加。
```

```text
confidence = sigmoid(base_score) = 1 / (1 + e^(-base_score))

或简化为 V1 线性裁剪：
confidence = clamp(base_score / 2.0, 0, 1)
```

V1 默认使用**线性裁剪**，便于调试；单条默认词面命中得到 `0.6`，恰好满足默认最小阈值。生产环境切换 sigmoid 前必须重新校准默认阈值，并以新的 engine_version 发布。

### 6.3 规则执行顺序

按 `priority ASC`（数值越小越优先）依次评估；同一候选内所有启用规则均参与计分，不因优先级提前终止（entity_exclusion 否决除外）。

---

## 7. 阶段 4：义项消歧

同一 `meme_id` 在相同 span 上可能命中多个 `sense_id`。

消歧策略（按优先级）：

```text
1. 取 confidence 最高的 sense；
2. 若 confidence 相同，取 sense 级规则命中数最多者；
3. 若最高分义项仍无法区分，输出一条词条级结果，`sense_id = null`、`ambiguous = true`，不把 `sense_no` 最小者描述为确定义项。
```

---

## 8. 阶段 5：多梗冲突处理

不同 `meme_id` 的命中 span 可能重叠。

### 8.1 重叠判定

```text
span A 与 span B 重叠：A.start < B.end && B.start < A.end
```

### 8.2 消解规则

按以下优先级保留胜出者：

```text
1. confidence 更高；
2. span 更长（覆盖更完整表达）；
3. priority 更高（取该 meme 最高优先级规则的 priority 值，数值越小越优先）；
4. meme_id 字典序（最终兜底）。
```

被压制者丢弃，不进入输出。

### 8.3 子串保留

若长 span 命中梗 A，短 span 在同位置命中梗 B，且 B.confidence - A.confidence >= 0.2，则同时保留 A 和 B（允许嵌套命中）。否则按 §8.2 消解。

---

## 9. 阶段 6：策略过滤

对剩余候选依次检查。风险策略先取词条级 `meme_safety_policies`，再使用命中义项的 `safety_policy_override` 覆盖已配置字段，得到本次命中的有效策略。

### 9.1 词条状态

| status | 默认识别 |
|---|---|
| `published` | 是 |
| `archived` | 是（历史内容识别） |
| `disabled` | 否 |

### 9.2 风险策略

```text
detect_enabled = 0 → 丢弃（不参与识别输出）；
detect_enabled = 1 → 保留，并在 policy 字段返回 display/generate/recommend 开关。
```

### 9.3 场景过滤（后续规划）

V1 不根据 `scene` 改变识别结果。后续需要支持审核、营销或其他差异化场景时，再定义各场景对 `risk_level`、`recommend_enabled` 等策略字段的过滤规则。

---

## 10. 阶段 7：阈值裁剪与排序

```text
1. 丢弃 confidence < options.min_confidence 的命中；
2. 按 confidence DESC、start_offset ASC 排序；
3. 截取前 options.max_results 条；
4. 附加 policy 快照（来自 meme_safety_policies）。
```

---

## 11. 典型示例

### 11.1 谐音梗识别

```text
输入：这薯片吃起来特别 treetree 的。
召回：normalized "treetree的" 命中变体
规则：normalized_match + positive_context（食物、吃、薯片）
结果：命中 treetree的，confidence >= 0.8
```

### 11.2 字面义排除

```text
输入：tree 是英语中"树"的意思。
召回：可能命中 treetree 相关变体（若子串匹配）
规则：entity_exclusion 命中（英语、意思、单词）
结果：entity_exclusion 否决，confidence = 0，不输出
```

### 11.3 多义消歧

```text
输入：对面把防御打穿了，我破防了。
召回：破防
义项 1：情绪破防；义项 2：游戏防御被突破
规则：义项 2 有 positive_context（防御、打穿）更高分
结果：输出义项 2
```

---

## 12. 数据加载与缓存

### 12.1 启动加载

```text
1. 加载 status IN (published, archived) 的 meme_entries；
2. 加载关联 senses、variants、match_rules、safety_policies；
3. 按原始文本、base、spacing、pinyin 四个索引构建 AC 自动机（变体和可索引词面规则 → meme_id, sense_id）；
4. 预编译 regex_match 规则。
```

### 12.2 刷新策略

```text
事件驱动：词条发布、停用、回滚时发送刷新信号；
定时兜底：每 5 分钟全量或增量刷新；
刷新期间使用双 buffer 切换，避免识别中断。
```

---

## 13. 评测与迭代

识别质量依赖 `meme_examples` 中的正例、反例和边界例。

建议建立评测集：

```text
1. 正例（positive）：应命中，测召回率；
2. 反例（negative）：不应命中，测精确率；
3. 边界例（boundary）：视 confidence 区间，测消歧能力。
```

每次修改匹配规则或归一化规范后，跑评测集并记录：

```text
Precision、Recall、F1（按 meme_id 或全局宏平均）
```

---

## 14. V1 范围与非目标

### V1 包含

```text
变体索引召回
精确 / 归一化 / 正则 / 拼音 / 上下文 / 实体排除规则
义项消歧与 span 冲突处理
风险策略过滤
```

### V1 不包含

```text
自动分词（V1 通过多模式匹配完成词面召回）
大规模语义召回（需向量服务，后续版本）
跨句指代消解
实时热梗发现
候选中或审核中的未发布内容识别
公共识别接口与内部调试接口的权限隔离
基于 `scene` 的差异化策略过滤
```

---

## 15. 版本记录

| 版本 | 日期 | 说明 |
|---|---|---|
| V1.0 | 2026-07-14 | 初始识别引擎规格 |
| V1.1 | 2026-07-15 | 补充词条级召回后的义项展开、歧义输出和义项级风险覆盖 |
