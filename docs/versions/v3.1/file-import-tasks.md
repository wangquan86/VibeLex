# VibeLex V3.1：文件导入任务化设计

**产品版本：** V3.1  
**适用来源：** CHIME、Buzzword  
**历史基线：** [V1.0 数据集导入设计](../v1.0/dataset-import.md)

---

## 1. 目标与边界

V3.1 将本地文件导入从一次请求内完成的同步处理，升级为“导入任务 + 逐条记录 + 后台 Worker”。本文件只记录 V3.1 相对 V1.0 的变化；V1.0 文档继续作为当时版本的历史设计快照。

本次改造解决以下问题：

- 管理页面可以查看每个文件任务和逐条处理结果；
- 测试导入可以软停止，避免继续领取记录和消耗 AI token；
- 临时错误自动重试，最终失败记录可以人工重新入队；
- 服务异常重启后，未结束的运行任务可以继续处理；
- 任务和记录均保留可审计的处理时间及 AI 追溯信息。

## 2. 数据模型

`source_import_runs` 继续表示一次文件导入任务，V3.1 新增 `source_import_records` 保存任务中的每个来源词条。逐条记录通过 `candidate_id` 关联成功创建的候选；重复、忽略和失败记录也必须保留。

任务新增或扩展以下信息：

- `attempt_no`：相同文件指纹的导入尝试序号；
- `imported_count`、`duplicate_count`、`ignored_count`、`failed_count`：逐条结果统计；
- `updated_at`：最近一次领取记录、统计变化或状态变化的时间。

逐条记录保存原始序号、来源记录键、词条、归一化词形、处理状态、阶段、尝试次数、租约、错误信息、候选或重复目标、AI 输出追溯字段，以及 `processed_at` 导入完成时间。

数据库结构以 [当前数据库模型](../../reference/database-schema.md) 和 Flyway V10、V11 为准。

## 3. 任务与记录状态

任务生命周期：

```text
planning → running → succeeded
                   ├→ partial_success
                   ├→ failed
                   └→ cancelled
```

`planning` 表示正在解析文件并建立逐条记录；超过 10 分钟仍未完成规划的任务标记为 `failed`。完成规划后进入 `running`，所有记录进入终态后再计算任务最终状态。

逐条记录状态固定为：

```text
pending       等待处理
processing    Worker 正在处理
imported      已创建候选
duplicate     已存在，未创建候选
ignored       原始数据无效或不适合导入
failed        达到最大尝试次数后仍失败
```

页面将 `pending` 和 `processing` 合并显示为“待处理”。`processor_stage` 只用于展示和排障，例如 `deduplicate`、`ai_enrichment` 和 `candidate_creation`。

## 4. 处理链与 AI 丰富化

通用 Worker 负责归一化、正式词条/变体/候选判重、租约、重试、状态统计和候选创建。来源可以注册可选的内容丰富化处理器：

```text
CHIME     解析 → 判重 → AI 起源检索与证据补充 → 校验 → 创建候选
Buzzword  解析 → 判重 → AI 起源检索与例句生成 → 校验 → 创建候选
```

只有判重未命中的记录才调用 AI。CHIME 严格保留原始释义和例句内容与顺序；AI 只补充起源说明和最多 3 条参考链接。Buzzword 保留原始词条与释义，由 AI 补充起源证据并生成恰好 3 条清洗后的例句。

AI provider、model、处理器版本、合法输出及起源引用保存在逐条记录和候选处理说明中，供后续审核和排障。

## 5. 幂等、重试与再次导入

文件级指纹保持为：

```text
source_name + source_version + file_hash
```

- 相同指纹存在 `planning`、`running`、`succeeded` 或 `partial_success` 任务时，直接返回原任务；
- 相同指纹只有 `failed` 或 `cancelled` 任务时，允许创建递增 `attempt_no` 的新任务；
- 再次导入仍执行正式词条、有效变体和全部候选判重，已有内容标记为 `duplicate`，不会重复调用 AI 或创建候选；
- 人工重试只重新入队当前任务中的 `failed` 记录，不处理 `imported`、`duplicate` 或 `ignored` 记录；
- 已取消任务不能通过失败记录重试恢复为 `running`，需要重新发起一次导入。

需要使用新版模型重新生成已有候选时，应使用独立的 AI 丰富化能力，不通过重复导入覆盖人工编辑内容。

## 6. 软停止语义

管理接口只允许停止 `running` 任务：

```http
POST /api/admin/imports/{runId}/cancel
```

停止后：

- 任务立即标记为 `cancelled`，并记录完成时间和更新时间；
- Worker 不再从该任务领取新记录；
- 已经领取的当前记录可能完成，包括已经发出的 AI 请求；停止操作无法追回已经消耗的 token；
- 服务重启后不会自动恢复该任务；
- 该任务不能再执行失败记录重试，但可以重新发起相同文件并产生新的 `attempt_no`。

这是协作式软停止，不强制中断线程或底层 HTTP 请求，避免引入复杂的进程级取消和事务补偿。

## 7. 管理页面

导入页面分为两层：

1. 任务列表显示来源、文件、状态、更新时间、总数、候选数、已存在、失败、拒绝数和发起人；运行中任务显示“停止任务”；
2. 词条列表显示来源序号、词条、处理结果、候选或重复目标、导入时间，并支持搜索、筛选、分页、详情和失败重试。

所有页面时间统一显示为 `yyyy-MM-dd HH:mm:ss`。导入时间使用逐条记录的 `processed_at`；任务更新时间使用 `source_import_runs.updated_at`。

候选词条列表另外显示候选 `created_at`，页面名称为“进入候选时间”。

## 8. 管理接口

```text
GET  /api/admin/imports
GET  /api/admin/imports/summary
GET  /api/admin/imports/{runId}/records
GET  /api/admin/imports/{runId}/records/{recordId}
POST /api/admin/imports/{runId}/retry
POST /api/admin/imports/{runId}/cancel
POST /api/admin/imports/{sourceCode}
```

完整请求和响应契约见 [V3.1 OpenAPI](openapi.yaml)。

## 9. 验收标准

1. CHIME 和 Buzzword 都可以创建导入任务并查看逐条记录；
2. 任务列表显示最近更新时间，逐条列表显示导入时间；
3. 运行中任务可以停止，停止后不再领取新记录；
4. 已停止任务在服务重启后不会恢复，也不能重试失败记录；
5. 已停止或失败的相同文件可以重新发起，并产生新的 `attempt_no`；
6. 临时错误可以自动重试，最终失败记录支持单条或批量重新入队；
7. CHIME 和 Buzzword 只对判重未命中的记录调用 AI；
8. CHIME 原始释义和例句保持不变，Buzzword 合法记录生成 3 条例句；
9. 重复、忽略和失败记录均保留并显示原因；
10. 导入处理状态与候选审核发布状态不混淆。
