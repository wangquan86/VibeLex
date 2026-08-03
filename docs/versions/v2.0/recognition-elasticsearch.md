# VibeLex V2：开放识别接口与 Elasticsearch 混合召回方案

**产品版本：** V2.0  
**状态：** 实现基线（封板候选）
**前置版本：** V1 规则识别引擎  
**向量模型：** `bge-large-zh-v1.5`（通过现有 embedding 服务访问）
**实现校准日期：** 2026-07-28

---

## 1. V2 定位与边界

V2 的目标不是建设 API 开放平台，也不是建设数据标注或模型训练平台；目标是将 V1 的规则识别能力升级为可运行、可降级、可排查的混合识别引擎。

```text
V1：已发布词条 + 变体/规则精确召回 + 上下文规则
V2：V1 规则召回 + ES 词法召回 + ES 语义候选召回 + 融合排序
```

V2 对外提供词条识别 REST API。接口暂不鉴权、限流、计费或管理调用方；部署边界为受控内网、测试环境或由网关隔离的环境。

### 1.1 V2 必须交付

- 版本化的词条识别 API 与完整 Markdown/OpenAPI 接口说明；
- 已发布词条义项的 Elasticsearch 检索投影；
- V1 规则、ES 词法、ES 语义三路候选召回与候选融合；
- `bge-large-zh-v1.5` embedding 服务适配器；
- 词条发布/变更后的索引同步、单条重建与全量重建；
- ES 或 embedding 服务异常时回退 V1 规则识别；
- 用于人工排查的召回来源、基础分数和淘汰原因。

### 1.2 V2 不做

- API Key、调用方、限流、配额、计费、调用日志产品化；
- 正式评测集、准确率 KPI、模型训练或微调；
- 自动采集、趋势系统、多租户、私有词库和行业词包；
- MQ、独立 Worker 等分布式设施。同步量确有瓶颈时再拆分；
- Elasticsearch 作为事实主库。MySQL 始终是词条事实和审核状态的权威来源。

---

## 2. 总体架构

```text
词条发布/修改/停用
        │
        ▼
MySQL（权威词条数据） ──► 索引投影器 ──► Elasticsearch（派生检索索引）
                                               │
调用方 ──► POST /api/v2/recognitions ──► V2 识别编排器
                                               │
                     ┌─────────────────────────┼───────────────────────┐
                     ▼                         ▼                       ▼
                 V1 规则召回              ES 词法召回              embedding + ES kNN
                     └─────────────────────────┴───────────────────────┘
                                               │
                                               ▼
             候选合并 → 消歧 → 上下文规则/策略过滤 → 排序 → 响应
```

ES 和 embedding 服务均为可降级依赖：ES 不可用时返回 V1 规则结果；embedding 不可用时保留 V1 规则和 ES 词法路径。外部依赖失败是否降级由配置控制，发生降级时响应返回 `degraded: true`。

---

## 3. 识别 API

机器可读的接口契约见 [V2.0 OpenAPI](openapi.yaml)，可导入 Apifox、Postman 或其他 OpenAPI 客户端使用。

### 3.1 接口

```http
POST /api/v2/recognitions
Content-Type: application/json
```

请求：

```json
{
  "text": "这个操作真的让我破防了，太绷不住了",
  "language_code": "zh-CN",
  "options": {
    "min_confidence": 0.6,
    "max_results": 20,
    "enable_semantic_recall": true
  }
}
```

字段约定：

| 字段 | 必填 | 说明 |
|---|---:|---|
| `text` | 是 | 待识别文本；V2 默认最大 480 字符，服务端以配置为准。 |
| `language_code` | 否 | 默认 `zh-CN`。V2 首期仅保证中文数据效果。 |
| `options.min_confidence` | 否 | 最终置信度下限，范围 `0..1`，默认来自配置。 |
| `options.max_results` | 否 | 最大返回数，范围 `1..200`。 |
| `options.enable_semantic_recall` | 否 | 调用方可请求关闭语义召回；服务端总开关关闭时始终不执行。 |

`start_offset` / `end_offset` 延续 V1 定义：以原始 `text` 的 Unicode 码点半开区间表示，不使用 UTF-8 字节或 UTF-16 下标。

### 3.2 响应

```json
{
  "request_id": "bfe4ae60-e186-4a60-a417-2b3a7ecb8c8c",
  "matches": [
    {
      "meme_id": 101,
      "meme_code": "MEME_000101",
      "canonical_term": "破防",
      "sense_id": 201,
      "sense_no": 1,
      "ambiguous": false,
      "matched_text": "破防",
      "start_offset": 7,
      "end_offset": 9,
      "confidence": 0.91,
      "match_reason": ["exact_match"],
      "recall_sources": ["rule", "lexical"],
      "policy": {
        "detect_enabled": true,
        "display_enabled": true,
        "generate_enabled": true,
        "recommend_enabled": true,
        "risk_level": "low",
        "moderation_policy": "allow"
      }
    }
  ],
  "engine_version": "2.0",
  "index_version": "vibelex_sense_current",
  "degraded": false,
  "processed_at": "2026-07-23T12:00:00Z"
}
```

所有返回结果都对应可定位的原文片段，并包含精确 offset；接口不返回 `match_type`。纯语义路径不能伪装为词面命中：V2.0 中纯语义召回仅作为内部候选，只有能通过标准词或变体锚定到原文时才进入最终识别流程。`index_version` 当前返回配置的 ES 查询别名，而不是物理索引名称。

### 3.3 错误约定

错误响应使用 Spring `ProblemDetail`，`Content-Type` 为 `application/problem+json`。当前实现不另设业务错误码。

| HTTP 状态 | `type` | 情况 |
|---:|---|---|
| 400 | `urn:vibelex:error:400` | 空文本、字段校验失败、V2 未启用，或关闭降级开关后的依赖调用失败。 |
| 413 | `urn:vibelex:error:413` | 文本超过配置上限。 |
| 500 | Spring 默认问题详情 | 未被业务异常处理器接管的服务端异常。 |

ES/embedding 异常触发降级时不返回错误：返回 `200`，并以 `degraded: true` 表示本次仅使用可用路径。

---

## 4. 混合识别流程

### 4.1 文本预处理

1. 保留原始文本和 Unicode 码点 offset 映射；
2. 复用 V1 的 `base`、`spacing`、`pinyin` 归一化视图；
3. 按标点切为句子/短片段，单片段不得超过 `sentence-max-characters`；
4. 规则路径仍可处理完整输入，词法/语义路径以片段为查询单位。

### 4.2 三路候选召回

| 路径 | 召回内容 | 作用 |
|---|---|---|
| `rule` | V1 变体、词面规则、正则 | 高精度主路径，保持既有能力。 |
| `lexical` | ES 中的标准词、变体、释义、标签 | 处理分词差异和弱词面相关性。 |
| `semantic` | 片段向量对义项向量 kNN | 发现没有显式词面的近义、解释性表达。 |

`semantic` 只能产生候选，不能跳过义项消歧、风险策略或阈值裁剪。高风险词条在 V2 默认禁止纯语义输出，至少须有规则、词法或上下文规则作为佐证。

V2 不使用 `meme_match_rules.semantic_threshold` 作为语义召回的开关或阈值。该值是 V1 遗留的数据库预留类型，运行时不执行；V2 统一使用 `vibelex.recognition.v2.elasticsearch.minimum-semantic-score` 控制语义候选的最低相似度。后续若确有义项级阈值需求，应新增明确的检索策略字段或配置模型，而不是重新启用该规则类型。

### 4.3 候选融合与最终输出

1. 以 `(meme_id, sense_id, 片段范围)` 合并三路候选；
2. 复用 V1 上下文规则、义项消歧、重叠处理与安全策略；
3. ES 分数用于候选召回和语义最低分过滤，不直接抬高最终置信度；最终分数仍由 V1 规则与上下文评分链产生；
4. 将结果裁剪到 `min_confidence` / `max_results`；
5. 对外响应返回最终结果的 `recall_sources`；召回分数、未锚定候选和过滤原因仅记录在内部诊断日志。

V2 首期不承诺准确率数值。实际使用中可从抽查记录逐步积累 case，不要求先建设正式标注集。

---

## 5. Elasticsearch 设计

### 5.1 索引边界

只投影符合 V1 识别条件的内容：

- `meme_entries.status IN (published, archived)`；
- `detect_enabled = true`；
- 词条、义项、变体、公开释义、场景、标签、风险策略和版本元数据。

不得投影候选池、审核意见、导入原文、内部证据全文或其他非公开信息。

一个“词条义项”一份 ES 文档；没有义项的词条以词条级虚拟义项建立文档。

### 5.2 文档示意

```json
{
  "document_id": "meme-101-sense-201",
  "meme_id": 101,
  "sense_id": 201,
  "meme_code": "MEME_000101",
  "canonical_term": "破防",
  "variants": ["破大防", "我破防了"],
  "definition": "因情绪受到强烈冲击而失去心理防线。",
  "scenes": ["comment", "live"],
  "tags": ["情绪", "调侃"],
  "risk_level": "low",
  "detect_enabled": true,
  "embedding": [0.01],
  "indexed_at": "2026-07-23T12:00:00Z"
}
```

`embedding_text` 由索引服务临时拼装并发送给 embedding 服务，不写入 ES `_source`；向量维度固定为 1024。

### 5.3 Mapping 原则

- `canonical_term`、`variants` 使用中文文本分析器，并保留 keyword 子字段；
- `definition`、`scenes`、`tags` 用于 BM25 词法召回；
- `embedding` 使用 `dense_vector`，`dims: 1024`，`similarity: cosine`，启用向量索引；
- `meme_id`、`sense_id` 使用数值字段，`meme_code`、`risk_level` 使用 keyword，`detect_enabled` 使用 boolean；
- 建议使用索引别名 `vibelex_sense_current`。全量重建写入新索引，校验完成后原子切换别名，避免查询中断。

### 5.4 同步与重建

词条首次发布、词条/义项/变体/规则改变、停用或归档时，必须更新或删除对应 ES 文档。`published` 和 `archived` 都属于正式词条投影范围；候选池不进入 ES。V2 初期可由同一应用进程内的应用服务完成：

```text
EntryPublished / EntryChanged / EntryDisabled
          ↓
读取 MySQL 已发布快照
          ↓
构造义项 embedding_text → 调用 embedding 服务
          ↓
ES upsert 或 delete
```

当前实现提供单词条同步、全量重建、失败记录、自动重试、人工重试和索引状态查询。词条事务提交后写入 `index_sync_tasks`，应用内定时任务批量领取并处理；最多自动尝试 5 次，失败任务可通过管理页面或接口重新入队。同步失败不回滚 MySQL 词条事务。全量重建为同步管理操作，使用时间戳物理索引并在完成后切换别名。

---

## 6. Embedding 服务接入

### 6.1 已确定模型与请求

模型采用 `bge-large-zh-v1.5`，当前服务请求模型名为 `bge-large-zh`。实际模型名以服务端契约为准，必须由配置文件提供，不能写死在业务代码中。

```bash
curl --location --request POST 'https://xmedia-t.api.leiniao.com/edu-embedding/embedding/get_vector' \
  --header 'Content-Type: application/json' \
  --data-raw '{
    "query": "擎天柱汽车人你还活着吗",
    "model_name": "bge-large-zh"
  }'
```

服务适配器职责：

- 发送 `query` 和 `model_name`；
- 校验 HTTP 成功、向量字段存在且维度严格为 1024；
- 对写入和查询两端使用完全相同的向量归一化策略；
- 将服务方实际响应结构隔离在 `EmbeddingProvider` 内，不泄漏到识别业务；
- 超时、网络错误、空向量或维度异常均作为 embedding 失败，触发本次请求降级。

当前适配器读取响应中的 `vector` 数组和可选 `dimension` 字段，并同时校验声明维度、实际数组长度和配置维度。服务契约变化时应先更新适配器测试，不应把服务方响应结构泄漏到识别业务。

### 6.2 向量输入规范

索引侧：使用“词条 + 变体 + 释义 + 场景 + 标签”拼装 `embedding_text`。不要只对词条名向量化。

查询侧：以预处理后的句子或短片段作为 `query`。过长文本应分片，避免语义被整段内容稀释；模型输入上限及截断策略由适配器统一控制。

---

## 7. 配置

所有 ES 与 embedding 参数放在 `src/main/resources/application.yml` 的 `vibelex.recognition.v2` 下。运行环境通过环境变量覆盖连接信息和敏感凭据：

```yaml
vibelex:
  recognition:
    v2:
      enabled: true
      semantic-recall-enabled: true
      elasticsearch:
        uris: ${VIBELEX_ES_URIS:http://10.145.12.11:9200/}
        index-name: vibelex_sense_v2
        index-alias: vibelex_sense_current
      embedding:
        endpoint: https://xmedia-t.api.leiniao.com/edu-embedding/embedding/get_vector
        model-name: bge-large-zh
        vector-dimension: 1024
        similarity: cosine
```

当前 ES 为内网 Elasticsearch 8.17.3 节点，地址为 `http://10.145.12.11:9200/`，无需账号密码，并已安装 `analysis-ik 8.17.3` 中文分词插件。Kibana 页面显示的 8.13.2 是 Kibana 自身版本，不作为 VibeLex ES 客户端版本依据。应用通过 `VIBELEX_ES_URIS` 环境变量覆盖该地址；ES 地址不得写入 Java 源码。若后续启用鉴权，再通过部署平台注入凭据。

---

## 8. 可用性、开关与排查

| 情况 | 行为 |
|---|---|
| `v2.enabled=false` | V2 路由仍存在，但识别请求返回 400；V1 保持原样。 |
| `semantic-recall-enabled=false` | V2 可使用规则/词法，跳过 embedding 和 kNN。 |
| ES 查询失败 | 按配置退化为 V1 规则结果，响应 `degraded=true`。 |
| embedding 调用失败 | 跳过本片段的语义召回，继续规则/词法路径。 |
| ES 同步失败 | 不影响词条事实发布；记录失败并允许重试。 |

当前识别日志包含请求 ID、词法/语义候选数量、锚定候选数量、最终结果数量、词法查询单元、部分顶部 ES 命中和降级状态。召回分数、逐候选淘汰原因和服务端分阶段耗时尚未形成稳定日志契约；管理页面展示的是浏览器侧端到端响应耗时。

---

## 9. 实施结果与封板标准

### 阶段 A：检索基础设施（已实现）

1. 引入 ES 客户端与配置绑定；
2. 实现 `EmbeddingProvider`、真实响应解析和 1024 维度校验；
3. 创建 mapping、义项投影器、单条/全量重建与别名切换；
4. 验证 MySQL 变更不受 ES 故障影响。

### 阶段 B：识别编排（已实现）

1. 新建 `/api/v2/recognitions`，保持 V1 API 不变；
2. 接入 ES 词法候选和语义 kNN 候选；
3. 实现候选融合、V1 规则复用、风险限制、输出模型和降级；
4. 增加结构化诊断日志和运行开关。

### 阶段 C：封板验证

1. 执行 `mvn test`，确保 V1 与现有业务回归通过；
2. 使用管理页面验证规则命中、词法命中、归一化 offset、空结果和参数校验；
3. 验证全量重建、别名状态、增量同步、失败任务重试以及 ES/embedding 故障降级；
4. 记录封板时的 ES 版本、IK 插件版本、embedding 模型名和关键配置；
5. 封板后新增固定 V2 回归样例集，再以补丁版本修复召回或文档缺陷。

> 当前仓库尚无 `recognitionv2` 专用自动化测试类；`mvn test` 只能证明既有测试集未回归。正式标记“已封板”前，应完成并记录上述人工联调项，或补充最小的 V2 编排、降级和 offset 自动化测试。该项是当前最明确的剩余质量门禁。

V2 完成不以预设准确率指标为条件。完成标准是：接口契约明确，已发布词条可稳定投影到 ES，三路候选能够接入同一流程，失败可降级，且出现误报或漏报时具备足够日志定位其来源和决策过程。

## 10. V2 候选并集与输出准入（实现口径）

V2 不是在 V1 已输出结果上附加 ES 标签。三个召回路径先各自产生候选，再进入**同一条** V1 校验链：

```text
规则候选  ─┐
ES 词法候选 ├─> 按 (meme_id, sense_id, 原文 span) 去重并合并来源
ES 语义候选 ┘                 ↓
                    上下文规则 → 义项消歧 → 重叠处理 → 风险策略 → 阈值/排序
                                              ↓
                                           最终结果
```

- 规则候选保留 V1 的原有能力和优先级，V2 不得因 ES 或 embedding 故障而减少规则命中。
- ES 词法候选仅在能以词条标准名或变体定位到原文片段时进入统一校验；ES 在释义、标签等字段上的弱相关命中只用于内部召回，不会直接生成对外结果。
- ES 语义候选不能裸返回。实现中必须同时取得原文词面锚点（记为 `lexical` 佐证）后，才会进入统一校验；没有锚点的近义命中会被丢弃并保留诊断计数。
- 相同候选的来源合并为 `recall_sources`，可为 `rule`、`lexical`、`semantic` 的组合；该字段说明候选证据，不替代最终的 `match_reason`、义项消歧和风险策略结果。

因此，正常服务可用时，V2 的最终结果应与 V1 持平或更多；ES 或 embedding 不可用时按配置降级为 V1，响应中的 `degraded=true` 明确标识本次降级。

### 10.1 词法查询与词面锚定

词法检索不把整段文本当作唯一查询。服务将输入按句末标点切句，并按逗号、顿号、分号切出短分句；每个单元同时生成原始文本、`BASE` 归一化文本和去除空格的 `SPACING` 归一化文本。所有查询单元通过 Elasticsearch `_msearch` 在**一次 HTTP 往返**中执行，再合并候选。

ES 召回的标准词或变体必须回到原文生成精确 `start_offset` / `end_offset`。这一步复用 V1 的归一化视图和 offset 映射，而不是直接字符串比较。例如 ES 标准词 `treetree的` 可锚定原文 `treetree 的`；对外仍返回原文片段和原文坐标。无法锚定的 ES 命中只保留为内部诊断候选，不得直接对外输出。
