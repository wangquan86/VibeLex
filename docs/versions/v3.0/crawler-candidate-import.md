# VibeLex V3：多来源爬取与候选词条导入设计

**产品版本：** V3.0

**状态：** 实现基线

**首个来源：** 波普词典（`https://www.popcidian.com/`）
**文档日期：** 2026-07-30

---

## 1. 目标

V3 建设一条简单、可重复使用的外部词条补充链路：

```text
外部网站
    ↓
统一来源同步（无检查点时枚举全部，有检查点时从检查点继续）
    ↓
解析词名和释义
    ↓
检查 VibeLex 是否已经存在
    ├─ 已存在 → 记录重复，结束
    └─ 不存在 → 创建 editing 候选，结束
```

核心规则只有一条：

> 每个来源页面只处理一次；词语只尝试入库一次。处理完成的来源记录进入永久终态。

### 1.1 V3.0 必须完成

- 波普词典网页手动同步与定时同步；
- 无检查点时自动枚举全部、有检查点时自动增量枚举；
- 持久化检查点；
- URL 级处理记录和失败重试；
- 正式词条、正式变体和候选词条判重；
- 非重复词自动进入 `candidate_entries.editing`；
- 复用现有人工审核发布流程；
- 为后续其他网站保留简单 Connector 扩展点。

## 2. 产品语义

### 2.1 “处理过”的定义

来源页面满足以下任一结果后，即视为永久处理完成：

```text
imported   已创建 VibeLex 候选
duplicate  VibeLex 中已经存在，因此跳过
ignored    来源页面不存在或没有可导入内容
```

终态记录的行为：

- 后续增量规划直接跳过；
- 来源 `lastmod` 和正文变化不改变终态；
- 已创建的候选或重复判断结果保持不变；
- 来源页面删除不改变 VibeLex 数据。

只有 `failed` 不是完成状态，允许后续重试。

### 2.2 “已经存在”的范围

以下任一位置命中即判定重复：

1. `meme_entries.normalized_term`；
2. `meme_variants.normalized_variant`，且变体状态有效；
3. `candidate_entries.normalized_term`，不区分候选状态。

候选状态包括 `editing`、`pending_review`、`returned` 和 `published`；判重查询不附加状态条件，任一状态命中都返回重复结果。

判重统一复用现有 `TermNormalizer`；Connector 只输出原始词名和释义。

### 2.3 单一同步模式

```text
checkpoint 为空    枚举来源全部内容
checkpoint 存在    只枚举检查点之后的内容
```

网页手动启动和定时器启动使用相同流程，不设置独立的全量完成标记。

---

## 3. 波普词典技术基线

2026-07-29 的只读探测结果：

| 项目 | 结果 |
|---|---|
| sitemap | `https://www.popcidian.com/sitemap.xml` |
| sitemap URL 总数 | 1,621 |
| `/entry/*` 词条 URL | 944 |
| 词条路径 | `/entry/{URL 编码词名}` |
| 增量字段 | sitemap `lastmod` |
| 页面框架 | Next.js |
| 详情接口 | `/api/v1/entries?name={term}` |

任务规模始终以当次 sitemap 为准，设计时快照仅用于容量估算。

波普 Connector：

- 从 sitemap 枚举 `/entry/*`；
- 使用 `lastmod` 生成增量游标；
- 使用词条路径中的词名请求详情 JSON 接口；
- 从 `result` 中匹配词名并提取 `term` 与 `chineseExplanation`。

---

## 4. 简化架构

```text
                           ┌──────────────────────┐
管理员 / 定时器 ─────────►│ CrawlExecutionService│
                           └──────────┬───────────┘
                                      │
                           ┌──────────▼───────────┐
                           │ CrawlConnector       │
                           │ enumerate + fetch    │
                           └──────────┬───────────┘
                                      │
                           ┌──────────▼───────────┐
                           │ crawl_records        │
                           │ URL 级检查点和队列   │
                           └──────────┬───────────┘
                                      │
                           ┌──────────▼───────────┐
                           │ CandidateService     │
                           │ 归一化 + 判重        │
                           └──────────┬───────────┘
                                      │
                       ┌──────────────┴──────────────┐
                       ▼                             ▼
              duplicate，处理结束          candidate_entries.editing
```

MySQL 同时承担当前任务状态、检查点、URL 队列和处理结果存储。

---

## 5. Connector 扩展方式

调度和入库流程不依赖具体网站。每个网站只实现一个 Connector：

```java
public interface CrawlConnector {
  String sourceCode();

  String sourceName();

  EnumerationResult enumerate(JsonNode checkpoint);

  CrawledEntry fetch(CrawlPointer pointer);

  default int maximumAttempts() { return 3; }
}
```

统一对象：

```java
public record CrawlPointer(
    String sourceRecordKey,
    String sourceUrl,
    Instant sourceModifiedAt) {}

public record EnumerationResult(
    List<CrawlPointer> items,
    JsonNode nextCheckpoint) {}

public record CrawledEntry(
    String term,
    String definition,
    List<String> examples,
    String category,
    String sourceCategory,
    List<String> sourceTags,
    String sourceUrl,
    String sourceRecordKey,
    String parserVersion) {}
```

首个实现：

```text
PopCidianConnector
├── sitemap 枚举
├── lastmod 检查点
├── JDK HttpClient 请求
└── 波普 JSON 详情接口解析
```

以后接入其他网站时，只新增类似：

```text
AnotherDictionaryConnector
├── 列表页或 sitemap 枚举
├── 自己的 checkpoint 格式
└── 自己的详情页解析
```

判重、候选创建、任务状态、重试和管理页面全部复用。

---

## 6. 数据库设计

新增两张表。

### 6.1 `crawl_checkpoints`

每个网站一条，保存来源标识、当前任务状态和同步检查点。来源 URL、展示名称、Cron、超时和单次枚举安全上限等静态配置由 Connector 与 `application.yml` 提供。

```sql
CREATE TABLE crawl_checkpoints (
  source_code VARCHAR(64) NOT NULL,
  checkpoint JSON NULL,
  pending_checkpoint JSON NULL,
  current_status VARCHAR(32) NOT NULL DEFAULT 'idle',
  discovered_count INT UNSIGNED NOT NULL DEFAULT 0,
  imported_count INT UNSIGNED NOT NULL DEFAULT 0,
  duplicate_count INT UNSIGNED NOT NULL DEFAULT 0,
  ignored_count INT UNSIGNED NOT NULL DEFAULT 0,
  failed_count INT UNSIGNED NOT NULL DEFAULT 0,
  lease_owner VARCHAR(128) NULL,
  lease_until DATETIME(3) NULL,
  error_summary VARCHAR(2000) NULL,
  started_at DATETIME(3) NULL,
  finished_at DATETIME(3) NULL,
  last_successful_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (source_code)
);
```

波普检查点示意：

```json
{
  "lastmod": "2026-07-29T03:00:00Z",
  "sourceRecordKey": "某词"
}
```

`checkpoint` 是最近一次成功任务的已提交游标；`pending_checkpoint` 是当前任务枚举得到的目标游标。当前任务全部完成后，后者才覆盖前者。

状态：

```text
current_status: idle | planning | running | partial | failed
```

### 6.2 `crawl_records`

每个来源 URL 一条，是处理队列，也是永久的“已经处理过”记录。

```sql
CREATE TABLE crawl_records (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  source_code VARCHAR(64) NOT NULL,
  source_record_key VARCHAR(512) NOT NULL,
  source_url VARCHAR(2048) NOT NULL,
  source_modified_at DATETIME(3) NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'pending',
  normalized_term VARCHAR(255) NULL,
  candidate_id BIGINT UNSIGNED NULL,
  duplicate_target_type VARCHAR(32) NULL,
  duplicate_target_id BIGINT UNSIGNED NULL,
  attempt_count INT UNSIGNED NOT NULL DEFAULT 0,
  next_attempt_at DATETIME(3) NULL,
  lease_owner VARCHAR(128) NULL,
  lease_until DATETIME(3) NULL,
  error_type VARCHAR(64) NULL,
  error_message VARCHAR(2000) NULL,
  processed_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT fk_crawl_record_checkpoint
    FOREIGN KEY (source_code) REFERENCES crawl_checkpoints(source_code)
    ON DELETE RESTRICT ON UPDATE CASCADE,
  CONSTRAINT fk_crawl_record_candidate
    FOREIGN KEY (candidate_id) REFERENCES candidate_entries(id)
    ON DELETE SET NULL ON UPDATE CASCADE,
  UNIQUE KEY uk_crawl_source_record(source_code, source_record_key),
  KEY idx_crawl_record_claim(source_code, status, next_attempt_at, lease_until),
  KEY idx_crawl_record_candidate(candidate_id),
  KEY idx_crawl_record_processed(processed_at)
);
```

状态：

```text
pending → processing → imported
                     → duplicate
                     → ignored
                     → retry_wait → processing
                     → failed
```

`imported`、`duplicate` 和 `ignored` 是永久终态，增量规划始终跳过这些记录。

---

## 7. 统一同步流程

管理员手动启动或定时器启动同一种同步任务：

```http
POST /api/admin/v3/crawl-sources/{sourceCode}/sync
```

流程：

1. 校验来源启用且没有活动任务；
2. 读取来源检查点；检查点为空时 Connector 枚举全部词条，否则只枚举检查点之后的词条；
3. 自动将该来源的失败记录重新入队；
4. 对每个 URL 执行 `INSERT IGNORE crawl_records(status=pending)`；
5. Worker 自动处理所有 `pending` 和可重试记录；
6. 每条记录独立提交，不等待整批完成；
7. 临时失败自动重试；
8. 全部记录进入永久终态后，任务状态恢复为 `idle` 并保存最大安全检查点。

首次同步不需要特殊模式；因为检查点为空，它自然处理全部 URL。内部逐条提交用于支持中断恢复。

如果应用重启，租约到期的 `processing` 记录重新进入处理队列，继续当前同步任务。

---

## 8. 有检查点时的同步流程

### 8.1 检查点枚举

网页操作和定时器都创建相同的同步任务：

```text
读取 crawl_checkpoints.checkpoint
        ↓
Connector 只枚举检查点之后的来源记录
        ↓
与 crawl_records 做 source_code + source_record_key 去重
        ↓
只为从未见过的 URL 创建 pending 记录
        ↓
只抓这些新 URL 的详情页
```

波普实现每次下载一次轻量 sitemap 元数据，详情页请求范围仅包含新 URL。

波普检查点筛选：

```text
source.lastmod > checkpoint.lastmod
或
source.lastmod = checkpoint.lastmod
且 source_record_key > checkpoint.source_record_key
```

当前实现使用严格复合游标，不额外设置重叠窗口；`crawl_records` 唯一键同时保证来源重复返回记录时不会造成重复抓取或重复候选。

### 8.2 已处理 URL

如果 URL 已存在于 `crawl_records` 且状态是：

```text
imported / duplicate / ignored
```

规划阶段直接跳过该记录，详情页请求只面向从未处理过的 URL。来源 `lastmod` 变化不改变终态。

### 8.3 检查点推进

只有本次新发现记录全部进入永久终态后，才把 `pending_checkpoint` 写入 `crawl_checkpoints.checkpoint`。

如果仍有 `failed`：

- 当前任务标记 `partial`；
- 不推进来源检查点；
- 下次运行时已成功记录被唯一键跳过；
- 失败记录重新入队；
- 失败清零后再推进检查点。

该规则保证部分页面失败时检查点仍覆盖全部待处理数据。

### 8.4 来源特殊情况

- 同一 URL 内容变化：已有处理记录，永久跳过；
- 同一 URL 词名变化：已有处理记录，永久跳过；
- 原 URL 删除：保留既有终态记录；
- 同一个词换成新 URL：新 URL 抓取一次，随后在 VibeLex 判重阶段被跳过；
- sitemap 重复返回旧 URL：由 `crawl_records` 唯一键跳过。

---

## 9. 页面处理与候选入库

每个新 URL 的处理事务：

```text
领取 crawl_record
        ↓
Connector.fetch 获取并解析详情页
        ↓
校验 term、definition
        ↓
TermNormalizer.normalize(term)
        ↓
查询正式词条、正式变体、候选词条
        ├─ 命中 → record=duplicate
        └─ 未命中 → 创建 candidate_entries.editing
                         ↓
                    record=imported
```

### 9.1 候选字段映射

| 抓取字段 | `candidate_entries` |
|---|---|
| `term` | `term_raw` |
| 归一化词名 | `normalized_term` |
| `definition` | `definition_raw` |
| `examples` | `processing_note.examples`，发布后写入 `meme_examples` |
| Connector 白名单映射分类 | `processing_note.category`，发布后写入 `meme_entries.category` |
| 来源原始分类 | `processing_note.source_category` |
| 来源标签 | `processing_note.source_tags`，发布后写入 `meme_entries.domain_tags` |
| `sourceUrl` | `source_url` |
| `sourceRecordKey` | 原值写入 `processing_note.source_record_key`；`candidate_entries.source_record_key` 写入来源代码与原值的 SHA-256 派生键 |
| Connector 解析器版本 | `parser_version` |
| `crawler` | `source_type` |
| `system` | `created_by` |
| `editing` | `status` |

`processing_note` 同时保存 Connector 提供的具体来源名称，候选池只展示“波普词典”等用户可读名称，不展示解析器版本或内部来源标识。

`import_run_id` 和 `import_fingerprint` 保持为空；来源处理关系由 `crawl_records` 承载。

候选创建统一通过新增的 `CandidateService.createFromCrawler(...)` 应用服务完成。

波普分类采用精确白名单映射：互联网黑话、网络用语和网络流行语映射为 `slang`；谐音类映射为 `homophone`；缩写类映射为 `abbreviation`；句式和模板类映射为 `template_phrase`。未配置的来源分类统一映射为 `other`，同时始终保留原始分类，不允许来源创建新的 VibeLex 分类。

### 9.2 原子性

创建候选与把记录标记为 `imported` 必须位于同一数据库事务中：

```text
再次执行最终判重
        ↓
INSERT candidate_entries
        ↓
UPDATE crawl_records
  SET status='imported', candidate_id=?
        ↓
COMMIT
```

并发情况下若最终判重发现其他任务已经创建同词候选，当前记录直接进入 `duplicate` 终态。

### 9.3 重复记录

判重时保存：

```text
normalized_term
duplicate_target_type = meme | variant | candidate
duplicate_target_id
processed_at
```

重复记录只保存来源标识、归一化词名和重复目标。

---

## 10. 详情抓取

波普 Connector 使用 JDK `HttpClient` 请求详情 JSON 接口：

```text
GET https://www.popcidian.com/api/v1/entries?name={term}
```

Connector 从 `result` 中匹配目标 `term`，提取 `term` 和 `chineseExplanation`。请求超时为 30 秒，临时错误最多尝试 3 次；空结果作为 `ignored`，缺少词名或中文释义作为处理失败。

---

## 11. 重试与中断恢复

### 11.1 错误处理

| 错误 | 处理 |
|---|---|
| 网络超时 | 进入 `retry_wait`，按阶梯时间退避 |
| 非 2xx HTTP 响应 | 作为当前记录失败处理并按统一规则重试 |
| JSON 解析失败 | 重试后仍失败则标记 `failed` |
| 词名或释义为空 | 标记 `failed`，不创建候选 |
| 页面不存在 | 标记 `ignored` 永久终态 |

当前退避阶梯为 30 秒、2 分钟、10 分钟；默认最多尝试 3 次，因此前两档用于自动重试，最后一档为提高最大尝试次数时的保留档位。

### 11.2 租约

Worker 领取记录时写入：

```text
status = processing
lease_owner = 当前实例
lease_until = 当前时间 + 2 分钟
```

进程退出后，租约到期的处理中记录可重新领取；终态记录始终从领取范围排除。

## 12. 调度配置

```yaml
vibelex:
  crawling:
    enabled: true
    worker:
      fixed-delay-millis: 3000
      lease-seconds: 120
      actor-id: system
    popcidian:
      enabled: true
      scheduled-enabled: false
      base-url: https://www.popcidian.com
      sitemap-url: https://www.popcidian.com/sitemap.xml
      sync-cron: "0 30 3 * * *"
      request-timeout-seconds: 30
      maximum-attempts: 3
      maximum-discovered-items: 5000
      user-agent: VibeLexCrawler/3.0
```

`scheduled-enabled` 只控制定时器是否自动发起同步，不影响管理页面手动操作；检查点为空时首次同步自然枚举全部内容。

---

## 13. 管理接口与页面

接口：

```http
GET  /api/admin/v3/crawl-sources
GET  /api/admin/v3/crawl-sources/{sourceCode}
POST /api/admin/v3/crawl-sources/{sourceCode}/sync
POST /api/admin/v3/crawl-sources/{sourceCode}/cancel
GET  /api/admin/v3/crawl-sources/{sourceCode}/records
GET  /api/admin/v3/crawl-sources/records?source={sourceCode|all}
```

页面展示：

- 右上角可选择具体来源或全部来源；全部来源只展示汇总，不能启动或取消任务；
- 具体来源名称、启用状态、同步状态、上次完成时间和同步进度；
- 当前来源或全部来源的累计处理、累计进入候选，以及具体来源的本次处理、本次进入候选；
- 按具体来源和简化处理结果查询记录；
- 失败 URL 与错误摘要；点击错误可查看并复制完整文本；
- 每条记录最终关联的候选或重复目标。

手动启动后如果没有发现新内容，页面明确提示检查点保持不变；如果没有待处理记录但来源最大游标发生变化，则提示检查点已更新。

---

## 14. 测试与验证

### 14.1 已自动化覆盖

- 波普 sitemap 解析和 `/entry/*` 过滤；
- 检查点为空时枚举全部 URL；
- 检查点存在时只枚举检查点之后的 URL；
- 复合检查点和最大检查点生成；
- Connector sitemap 与 JSON fixture 解析；
- 来源分类白名单映射和原始分类保留；
- 存在活动记录时不推进检查点；
- 存在失败记录时保持检查点并进入 `partial`；
- 全部记录进入终态后推进检查点；
- 启动统一同步时自动重新入队失败记录；
- 正式词条、正式变体和候选词条判重；
- 爬取候选的来源名称、分类、标签和例句映射。

### 14.2 回归验证重点

- 检查点为空时一次同步自动处理全部计划记录；
- 中途重启后继续原任务；
- 检查点存在时只枚举检查点之后的记录；
- 后续同步不抓已经处理过的 URL；
- 来源 `lastmod` 改变但 URL 已处理时直接跳过；
- 新 URL 且新词创建一个候选；
- 新 URL 但词语重复时标记 `duplicate`；
- 创建候选与记录终态保持事务一致；
- 部分失败时不推进检查点；
- 重试成功后推进检查点；
- 两个来源出现同一词时，第二条记录进入 `duplicate` 终态。

### 14.3 在线验证

1. 使用固定词条验证波普 JSON 详情接口字段；
2. 使用前 20 个 URL 验证解析、判重和候选映射；
3. 使用前 100 个 URL 验证请求稳定性、重试和断点续跑；
4. 清理试验数据；
5. 管理员执行一次正式同步，检查点为空时预期枚举全部内容；
6. 处理完失败记录后确认检查点已推进；
7. 立即再次同步，预期不抓旧详情页；
8. 用 fixture 增加一个新 URL，验证只创建一个新候选。

---

## 15. 实施阶段

### 阶段 A：通用最小骨架

- 创建 `crawl_checkpoints` 和 `crawl_records`；
- 实现 Connector 接口；
- 实现运行、检查点、URL 去重、租约和重试；
- 实现 `CandidateService.createFromCrawler(...)`。

### 阶段 B：波普 Connector

- 实现 sitemap 无检查点枚举和检查点增量枚举；
- 完成 `HttpClient` 详情请求和 JSON 解析；
- 完成 5、20、100 条验证；
- 固化解析 fixture。

### 阶段 C：正式运行

- 执行首次正式同步；
- 处理失败项直至检查点推进；
- 开启定时同步；
- 完成管理页面、日志和运行手册。

### 阶段 D：接入其他网站

- 新增来源配置；
- 实现新的 `CrawlConnector`；
- 复用既有运行、检查点、判重和候选导入能力；
- 通过小规模验证后启动该来源同步。

---

## 16. 验收标准

1. 波普词典在检查点为空时可以通过一次同步处理全部词条；
2. 同步支持自动重试和断点续跑；
3. 同步成功后持久化检查点，手动与定时启动采用相同流程；
4. 有检查点时只抓检查点之后且从未处理过的 URL；
5. 已处理 URL 保持永久终态并在增量规划时跳过；
6. 来源变化或删除时保留既有 VibeLex 数据；
7. 正式词条、正式变体和候选词条判重有效；
8. 非重复词自动进入 `candidate_entries.editing`；
9. 重复词进入 `duplicate` 终态，并能查看重复目标；
10. 部分失败时检查点保持原值，失败记录可以继续处理；
11. 重复运行不产生重复记录或重复候选；
12. 爬虫故障不影响 V1/V2 服务；
13. 第二个网站只需增加来源配置和 Connector，不修改公共流程。
