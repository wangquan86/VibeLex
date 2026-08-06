# VibeLex V3.2：上下文词条推荐 API 设计

**产品版本：** V3.2

**状态：** 待评审

**文档日期：** 2026-08-04

---

## 1. 目标

V3.2 新增一个基于自然语言上下文的词条推荐 API。调用方提交剧情、台词、事件或对话上下文，VibeLex 从已发布词条中返回语义相关的候选梗列表。

首期覆盖两类调用方，但 API 本身不绑定具体应用：

1. 弹幕智能体：根据影片片段的剧情、台词和事件检索候选梗；
2. 聊天智能体：根据用户对话上下文检索候选梗。

```text
调用方整理上下文
       ↓
POST /api/v3/recommendations
       ↓
上下文向量化 ──→ 语义 kNN 召回
       └────────→ 词法 BM25 召回
                         ↓
                  加权 RRF 融合
                         ↓
            词条准入过滤、按词条去重
                         ↓
                返回结构化候选列表
```

推荐 API 与现有 V2 识别 API 的目标不同：

| 能力 | 输入与输出关系 | 是否要求词条出现在原文中 |
|---|---|---:|
| V2 识别 `/api/v2/recognitions` | 找出文本中已经使用的梗 | 是，必须能定位原文 offset |
| V3 推荐 `/api/v3/recommendations` | 找出适合当前上下文使用的梗 | 否，允许纯语义命中 |

V3.2 不复用 V2 的“原文锚定”准入链。两者复用 Elasticsearch 义项索引和 embedding 服务，但使用独立的查询、排序和输出模型。

---

## 2. 本期建设范围

- 一个场景无关的上下文词条推荐 REST API；
- 基于现有 Elasticsearch 与 BGE embedding 的语义、词法双路召回；
- 稳定、可解释的候选融合、义项选择和词条去重规则；
- 仅返回已发布且义项有效的正式词条；
- ES 索引补充推荐所需字段，并沿用现有全量重建和增量同步机制；
- embedding、Elasticsearch 或词法增强异常时的明确处理行为；
- OpenAPI 契约和覆盖推荐逻辑的自动化测试；
- 请求上下文仅用于本次检索，不写入数据库或日志。

调用方应在请求前自行选择和整理与当前任务有关的上下文。VibeLex 不区分该文本来自影片、弹幕还是聊天，也不解析调用方内部的消息对象。

---

## 3. API 契约

### 3.1 接口

```http
POST /api/v3/recommendations
Content-Type: application/json
```

API 路径只使用主版本号 `v3`，产品补丁版本不进入 URL。V3.2 发布后，该契约随 V3 API 保持兼容。

### 3.2 请求

```json
{
  "context": "主角连续尝试三次都失败了，嘴上说没事，转身后却坐在台阶上怀疑人生。",
  "language_code": "zh-CN",
  "max_results": 10
}
```

| 字段 | 必填 | 约束 | 说明 |
|---|---:|---|---|
| `context` | 是 | 去除首尾空白后非空；最多 480 个 Unicode 码点 | 调用方整理好的单段上下文；服务端不静默截断 |
| `language_code` | 否 | V3.2 仅接受 `zh-CN` | 默认 `zh-CN` |
| `max_results` | 否 | `1..20` | 默认返回 10 个不同词条 |

V3.2 不提供请求级 `min_score`。融合分数是同一次请求内的相对排序分，不是跨请求稳定的置信度。

聊天调用方可以将必要轮次整理为普通文本，例如：

```text
用户：我准备了一个月，结果比赛第一轮就被淘汰了。
助手：听起来很失落。
用户：现在只想找个洞钻进去。
```

角色选择、轮次裁剪和顺序保留属于调用方的上下文管理职责。

### 3.3 成功响应

```json
{
  "request_id": "4dfcd136-60ce-4b46-a424-39494075d0f5",
  "recommendations": [
    {
      "meme_id": 101,
      "meme_code": "MEME_000101",
      "canonical_term": "破防",
      "variants": ["我破防了"],
      "sense_id": 201,
      "sense_no": 1,
      "definition": "因受到强烈情绪冲击而失去心理防线，常用于表达受打击、感动或自嘲。",
      "examples": ["看到最后这一幕，我真的破防了。", "努力这么久还是失败，属实有点破防。"],
      "category": "emotion_expression",
      "domain_tags": ["情绪表达", "网络聊天"],
      "origin": {
        "summary": "该表达由游戏中的防御被突破引申而来。",
        "evidence": [
          {
            "source_name": "来源名称",
            "source_url": "https://example.com/origin",
            "source_layer": "dictionary",
            "note": "支持该起源结论的证据摘要",
            "observed_at": "2026-08-04T04:00:00Z",
            "confidence": 0.9
          }
        ]
      },
      "relevance_score": 0.873421
    }
  ],
  "engine_version": "3.2",
  "index_version": "vibelex_sense_current",
  "processed_at": "2026-08-04T04:00:00Z"
}
```

响应约定：

- `recommendations` 按 `relevance_score` 降序排列；
- 同一 `meme_id` 最多返回一次，保留当前上下文下排名最高的义项；
- `relevance_score` 范围为 `0..1`，保留 6 位小数；启用 Reranker 且调用成功时表示模型相关性分数，关闭或降级时表示 RRF 融合分数；
- `variants` 返回当前义项可使用的有效变体；
- `examples` 最多返回 3 条当前义项可用的已审核正向例句；
- `domain_tags` 没有数据时返回空数组；
- 正常完成但没有合格候选时返回 `200` 和空数组，不将“无结果”视为异常；
- 服务端 `vibelex.recommendation.v3.origin.enabled` 默认开启；开启时批量补充词条起源说明和当前义项适用的有效起源证据，关闭时省略 `origin` 且不查询起源数据；
- 起源补充失败时保留推荐结果并省略 `origin`，API 始终不返回内部召回来源、原始 ES 分数或被过滤候选。

### 3.4 错误响应

继续使用 Spring `ProblemDetail`，响应类型为 `application/problem+json`。

| HTTP 状态 | 情况 |
|---:|---|
| `400` | 上下文为空、语言不支持、`max_results` 越界或字段校验失败 |
| `413` | `context` 超过配置的 Unicode 码点上限 |
| `503` | 推荐功能未启用、embedding 不可用或 Elasticsearch 语义检索不可用 |

语义依赖不可用时不得返回 `200` 空数组伪装成“没有相关推荐”。V3.2 需要新增明确的推荐不可用异常，并由全局异常处理器映射为 `503`。

---

## 4. 推荐流程

### 4.1 输入处理

1. 校验 `context`、`language_code` 和 `max_results`；
2. 使用 Unicode 码点而不是 UTF-8 字节或 UTF-16 下标计算长度；
3. 仅去除首尾空白，不删除角色名、标点或换行；
4. 完整上下文作为一个语义查询单元生成一个向量；
5. 完整上下文作为一个词法查询单元，不沿用 V2 识别的逐句原文锚定流程。

V3.2 将最大长度设为 480，是为了与当前 BGE 查询输入能力和 V2 已验证的长度边界保持一致。服务端不做无法向调用方解释的头部/尾部截断。

### 4.2 语义召回

调用现有 `EmbeddingProvider` 生成上下文向量，再对义项文档执行 Elasticsearch kNN 查询。

共享索引在构建阶段限定为已发布或已归档词条的有效义项。语义查询应用以下过滤条件：

- `entry_status = published`；
- `language_code = zh-CN`。

语义候选低于服务端 `minimum-semantic-score` 时丢弃。该阈值只做全局配置，不在 V3.2 API 中开放给调用方。

### 4.3 词法召回

词法召回用于补强上下文中已有的主题词、情绪词和事件词，不以发现原文中是否出现某个梗为目标。建议字段权重为：

```text
definition^4
examples^3
domain_tags^2
tags^2
canonical_term^1
variants^1
```

词法查询使用与语义查询相同的词条状态和语言过滤条件。检索字段以当前数据来源能够稳定提供的完整释义、已审核例句、变体和领域标签为主。

### 4.4 候选融合

语义分数和 BM25 分数不在同一数值空间，不能直接相加。V3.2 在应用服务中使用加权 Reciprocal Rank Fusion（RRF）：

```text
score = semantic_weight × (k + 1) / (k + semantic_rank)
      + lexical_weight  × (k + 1) / (k + lexical_rank)
```

首期参数：

```text
k               = 60
semantic_weight = 0.7
lexical_weight  = 0.3
```

候选未出现在某条路径时，该路径贡献为 0。两个权重之和为 1，因此理论最高分为 1。参数由服务端配置控制，不允许请求覆盖。

融合与输出顺序如下：

1. 以 `(meme_id, sense_id)` 合并双路候选，并在内部保留召回来源；
2. 校验候选包含有效词条和义项标识；
3. 按融合分数降序排列；
4. 分数相同时依次按语义原始分、`meme_id`、`sense_id` 确定稳定顺序；
5. 同一 `meme_id` 只保留最高排名义项；
6. 截取前 `max_results` 个不同词条，作为本次重排序候选。

V3.2 不用热度、新鲜度或随机数打破并列，避免结果难以复现。

### 4.5 候选重排序

RRF 负责从语义与词法双路结果中召回候选。重排序开启时，将 4.4 得到的候选一次性提交给 CPU Reranker，不逐条调用。候选数由请求的 `max_results` 决定；实际候选不足时全部提交，不设置额外的固定候选数。

每个候选文本包含归一化词条名、有效变体、释义和最多 3 条典型用法。最终按 Reranker 分数降序排列并将该分数写入 `relevance_score`；模型分数相同时保持 RRF 顺序。重排序关闭时直接返回 RRF 顺序；连接超时、请求超时、非 2xx、结果数量不符、候选索引无效或分数无效时，也回退 RRF 顺序，不阻断推荐接口。

---

## 5. Elasticsearch 索引调整

### 5.1 复用边界

继续复用当前别名 `vibelex_sense_current`，保持“一条有效义项一份 ES 文档”。MySQL 仍是权威数据源，Elasticsearch 仍是可重建的检索投影。

V3.2 不新增 MySQL 表，但需要通过 Flyway 删除 `meme_safety_policies` 中的 `detect_enabled`、`generate_enabled`、`recommend_enabled`，同时删除对应检查约束和联合索引。ES 必须重新定义 mapping 和索引文档。由于当前 mapping 使用 `dynamic: strict`，上线前必须创建新物理索引、全量写入并原子切换别名，不能直接修改旧索引。

### 5.2 共享文档字段

```json
{
  "meme_id": 101,
  "sense_id": 201,
  "sense_no": 1,
  "meme_code": "MEME_000101",
  "canonical_term": "破防",
  "variants": ["我破防了"],
  "language_code": "zh-CN",
  "entry_status": "published",
  "category": "emotion_expression",
  "domain_tags": ["情绪表达", "网络聊天"],
  "definition": "因受到强烈情绪冲击而失去心理防线，常用于表达受打击、感动或自嘲。",
  "examples": ["看到最后这一幕，我真的破防了。", "努力这么久还是失败，属实有点破防。"],
  "embedding": [0.01]
}
```

V2 与 V3 一起改用该文档结构，不再保留旧 mapping 中的 `scenes`、`tags`、`risk_level` 和 `indexed_at`。当前 V2 对 `tags`、`scenes` 的查询改为使用真实有数据的 `definition`、`examples` 和 `domain_tags`。

`examples` 只投影 `status=approved`、`example_role=positive` 且适用于当前义项的例句：`sense_id` 为空或等于当前 `sense_id`，按数据库顺序最多取 3 条。

共享索引严格保持“一条 active 义项一份文档”，不再创建 `sense_id` 为空的词条级虚拟文档。没有 active 义项的正式词条不进入索引，并在全量重建报告中列出。

### 5.3 索引准入条件

共享索引只投影正式词条的有效义项：

```text
meme_entries.status IN (published, archived)
AND meme_senses.status = active
```

共享索引同时保存 `published` 和 `archived`，并在文档中保存 `entry_status`。V2 查询两种正式状态并继续执行原文锚定；普通词条检索也可以查询归档词条；V3 额外过滤 `entry_status=published`，不主动推荐归档词条。

`detect_enabled` 没有独立的数据治理来源，并且与“归档词条仍允许识别”的产品规则冲突；`generate_enabled` 和 `recommend_enabled` 只在发布时自动赋值并作为 V2 响应元数据，不参与任何识别、检索、排序或过滤。本期删除这三个字段，同时移除候选发布赋值、归档更新、V1/V2 读取、ES mapping/查询过滤、V2 响应字段和管理页面展示。义项级 `status` 继续保留，只有 `active` 义项进入共享索引。

### 5.4 V2 识别调整

V2 外部路径继续使用 `POST /api/v2/recognitions`，原文锚定、offset、上下文规则、义项消歧、重叠处理和置信度流程保持不变。索引相关实现调整为：

- MySQL 规则索引加载 `published` 和 `archived` 的正式词条，只加载 `active` 义项；
- ES 词法召回使用 `canonical_term`、`variants`、`definition`、`examples` 和 `domain_tags`，其中词条名和变体保持高权重；
- ES 语义召回使用新的共享义项向量；
- 纯语义候选仍必须通过词条名或变体锚定到原文后才能成为 V2 结果；
- V2 响应的 `policy` 中删除 `detect_enabled`、`generate_enabled` 和 `recommend_enabled`；
- 已归档词条继续允许识别。

### 5.5 Embedding 文本

每个义项的向量文本固定拼接以下已有字段：

```text
词条 + 变体 + 完整释义 + 分类 + 领域标签
```

不拼接来源原文、起源背景、审核备注和风险备注。索引端和查询端继续使用同一 embedding 模型、向量维度和归一化约定。

现有 `index_sync_tasks` 继续负责增量同步；全量重建使用独立的 `search_rebuild_jobs` 和 `search_rebuild_items` 任务表，以分批、可重试的方式写入临时索引，全部成功后再切换别名。全量任务处于 `preparing` 或 `running` 时，增量任务继续入队但暂停消费；切换成功或全量失败后恢复消费。词条从 `published` 变为 `archived` 时不得删除索引文档，而应把文档的 `entry_status` 更新为 `archived`；义项变为非 `active` 或词条离开正式状态范围时，删除对应文档。

---

## 6. 模块与配置

### 6.1 代码边界

索引和 embedding 从 `recognitionv2` 提升为共享搜索模块，V2 识别和 V3 推荐分别负责各自编排：

```text
search/SearchIndexService
        负责共享义项索引的 mapping、全量重建和增量同步

search/ElasticsearchGateway
        负责共享 ES 查询与索引操作

search/EmbeddingProvider
        负责统一的向量服务适配

recognitionv2/RecognitionV2Service
        负责 V2 召回、原文锚定和现有识别流水线

recommendation/api/RecommendationController
        负责 HTTP、校验和响应契约

recommendation/application/RecommendationService
        负责编排双路召回、融合、状态过滤和去重

recommendation/application/RecommendationProperties
        负责 V3 推荐参数
```

### 6.2 配置建议

```yaml
vibelex:
  recommendation:
    v3:
      enabled: true
      max-context-characters: 480
      default-max-results: 10
      max-results-limit: 20
      semantic-top-k: 50
      lexical-top-k: 50
      minimum-semantic-score: 0.65
      rrf-rank-constant: 60
      semantic-weight: 0.7
      lexical-weight: 0.3
      reranker:
        enabled: true
        endpoint: http://10.145.12.11:8082
        connect-timeout-millis: 1000
        request-timeout-millis: 10000
```

ES 地址、索引别名和 embedding 服务连接信息移动到共享 `vibelex.search` 配置，继续复用现有环境变量，避免部署环境重复配置。V2 和 V3 各自保留业务开关与查询参数。

参数合法性在应用启动时校验，至少包括：权重非负且总和为 1、Top K 不小于最大返回数、长度和数量上限为正数。

---

## 7. 可用性、日志与隐私

### 7.1 可用性矩阵

| 状态 | API 行为 |
|---|---|
| 语义、词法均成功 | `200`，返回融合结果 |
| embedding 失败 | `503`，不使用词法结果替代语义推荐 |
| Elasticsearch 语义查询失败 | `503` |
| 词法查询失败，语义成功 | `200`，返回语义结果并在服务端记录故障 |
| Reranker 关闭 | `200`，返回 RRF 排序结果 |
| Reranker 调用或响应异常 | `200`，回退 RRF 排序并在服务端记录故障 |
| 正常检索但没有合格候选 | `200`，返回空列表 |
| `vibelex.recommendation.v3.enabled=false` | `503` |

语义检索是 V3.2 的核心能力。语义路径不可用时不回退到词法搜索、MySQL 全表扫描或 V1 规则识别。词法召回只是排序增强路径，其故障不改变语义结果的业务契约。

### 7.2 日志

每次请求记录：

- `request_id`；
- 上下文码点长度，不记录上下文正文；
- 语义、词法原始候选数和融合后候选数；
- 安全过滤数、词条去重数和最终返回数；
- embedding、ES、融合、Reranker 和总耗时；
- Reranker 是否尝试及是否成功；
- embedding 和 Elasticsearch 各阶段的成功状态及失败原因；
- `engine_version` 和索引别名。

日志不得记录用户对话、影片台词、完整 ES 查询体或 embedding 向量。V3.2 不新增推荐请求历史表。

---

## 8. 测试与验收

### 8.1 自动化测试

- 请求字段、Unicode 长度、默认值和上限校验；
- 语义/词法候选合并、加权 RRF 计算和稳定排序；
- 同一词条多个义项只返回最高排名义项；
- `published`、`archived` 和 `active` 义项的索引准入，以及 V3 对归档词条的排除；
- 三个冗余策略字段的数据库迁移、发布/归档流程和 V2 契约清理；
- embedding 或语义查询失败时返回 `503`，词法失败时仍返回语义结果；
- 正常空结果与依赖失败能够区分；
- 索引全量重建和增量同步包含新增字段；
- V2 识别回归测试，确保共享索引调整不改变原文锚定规则；
- OpenAPI 契约示例能够通过 schema 校验。

### 8.2 检索逻辑验收

V3.2 只验证数据是否按照设计逻辑进入索引、参与检索并形成响应：

- `published + active` 义项能够进入 V3 推荐；
- `archived` 义项仍能被 V2 识别，但不会进入 V3 推荐；
- 非正式词条和非 `active` 义项不会进入共享索引；
- 语义候选不需要出现在原文中即可进入 V3 融合排序；
- V2 语义候选仍必须锚定原文后才能输出；
- 语义和词法候选按照加权 RRF、义项合并和词条去重规则排序；
- 返回的词条、义项、变体、释义、例句、分类和领域标签与 MySQL 正式数据一致；
- 正常空结果、语义依赖不可用和字段校验错误符合 API 契约。

V3.2 不建立人工标注集，不量化召回率、准确率或 Top-K 命中率。推荐结果的实际语义效果由用户在使用过程中主观评估。

### 8.3 性能验收

在目标部署环境、依赖正常且上下文不超过 480 码点时，记录至少 200 次连续请求：

- 建议服务端总耗时 P95 不高于 1.5 秒；
- 单次请求只调用一次 embedding 服务；
- 语义和词法检索各最多一次 ES 往返；
- API 不产生 MySQL 逐结果查询。

若现有 embedding 服务无法达到耗时门槛，应先记录依赖分段耗时并单独评审，不在 V3.2 内通过引入缓存或新基础设施掩盖问题。

---

## 9. 实施顺序

### 阶段 A：索引契约

1. 新增 Flyway 迁移并删除 `detect_enabled`、`generate_enabled`、`recommend_enabled`；
2. 建立共享 search 模块，重写 ES mapping 和义项投影；
3. 调整 V2 的 MySQL 规则索引、ES 查询和响应契约；
4. 创建新物理索引并全量重建；
5. 校验文档数量、向量维度并完成 V2 识别回归；
6. 原子切换 `vibelex_sense_current` 后立即删除旧物理索引。

### 阶段 B：推荐 API

1. 新增推荐配置、请求/响应模型和 Controller；
2. 实现语义、词法召回和加权 RRF；
3. 实现状态过滤、义项合并、词条去重和依赖异常处理；
4. 补充 API、服务和异常路径测试。

### 阶段 C：契约与封板

1. 新增 `docs/versions/v3.2/openapi.yaml`；
2. 执行索引准入、检索排序和响应映射的逻辑验收；
3. 记录性能基线和关键配置；
4. 新增 V3.2 发布说明和部署步骤；
5. 更新项目版本与 README 后封板。

上线时先保持 `vibelex.recommendation.v3.enabled=false`，完成新索引重建、V2 回归、别名切换和旧索引删除后再启用推荐接口。

---

## 10. 完成标准

V3.2 完成不以“接口能够返回若干词条”为唯一标准。以下条件必须同时满足：

1. 调用方能用一段中文上下文获得结构化、去重、按相关性排序的词条列表；
2. 纯语义相关但未出现在原文中的词条可以进入结果；
3. 只有已发布的 active 义项可以进入推荐结果，已归档义项仍可供 V2 识别但不会被主动推荐；
4. 推荐链路不生成文案、不调用 LLM、不承担应用层智能体逻辑；
5. 正常空结果与语义服务不可用具有明确且不同的契约；
6. V2 识别能力在共享索引扩展后保持兼容；
7. OpenAPI、自动化测试、检索逻辑验收和部署步骤齐全。
