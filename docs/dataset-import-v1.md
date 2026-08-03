# VibeLex 多来源数据集导入设计 V1.0

**项目名称：** VibeLex  
**产品版本：** V1.0  
**文档修订：** 1.0  
**状态：** 已实现

---

## 1. 文档目的

本文说明 VibeLex V1.0 如何将经过权利核验的本地数据文件转换为可编辑候选词条，重点覆盖：

- 通用导入编排与来源专属 Importer 的职责边界；
- CHIME、Buzzword JSON 的实际字段映射；
- 文件校验、许可证门禁、幂等、错误隔离和运行统计；
- 导入候选如何进入现有编辑、提交审核和正式发布流程；
- 后续新增数据来源时必须遵循的扩展方式。

本文是技术实现设计，不负责判断某个来源是否可以使用。来源许可证、隐私、留存和使用边界由 [data-source-governance.md](data-source-governance.md) 统一规定。

---

## 2. V1.0 范围与非目标

### 2.1 已实现范围

- 操作者将数据文件放入项目 `data/` 目录，并在管理页面选择来源和文件；
- 通过 `CandidateImporter` 为每个来源提供独立解析器；
- 通用服务统一执行文件校验、许可证门禁、文件哈希、导入指纹、运行记录、归一化、正式词条重复提示和候选写入；
- 支持 CHIME、Buzzword JSON 数组；
- 单条数据失败时记录错误并继续处理其他记录；
- 相同来源版本和文件内容重复提交时复用已有运行；
- 所有导入结果只进入 `candidate_entries`，不自动发布正式词条。

### 2.2 非目标

- 自动下载 Hugging Face 或其他远程数据集；
- 网页爬虫、浏览器自动化、登录态采集或反爬绕过；
- 定时导入、后台 Worker、消息队列、自动重试和自动补偿；
- 使用 AI 自动判断许可证是否有效；
- 从对话数据自动生成可信释义、起源或风险结论；
- 绕过候选编辑和人工审核直接写入正式词条；
- 建设通用来源管理、自动采集任务或趋势数据管线。

---

## 3. 与其他文档的边界

| 文档 | 负责内容 |
|---|---|
| [data-source-governance.md](data-source-governance.md) | 来源是否允许使用、许可证、隐私、留存和禁止事项 |
| 本文档 | 获准文件如何解析、记录运行并进入候选池 |
| [system-architecture-v1.md](system-architecture-v1.md) | 导入域与候选域、审核域、正式词条域的模块边界 |
| [database-design.md](database-design.md) | `source_import_runs`、`candidate_entries` 等表结构 |

导入模块必须执行治理门禁，但不自行作出法律判断：

```text
来源完成权利核验
→ license_status = approved
→ 允许执行对应 Importer
→ 生成候选词条
```

`pending`、`rejected` 或 `expired` 状态均不得开始导入。

---

## 4. 当前组件与职责

```text
ImportController
  ↓ 选择 source code
SourceImportService
  ├── 文件路径与大小校验
  ├── 许可证门禁
  ├── 文件哈希与导入指纹
  ├── source_import_runs
  ├── 词形归一化与正式词条重复提示
  └── candidate_entries 持久化
        ↑
CandidateImporter
  └── ChimeImporter
```

### 4.1 `ImportController`

管理接口包括：

```text
GET  /api/admin/imports/sources
GET  /api/admin/imports/files?source={sourceCode}
GET  /api/admin/imports
POST /api/admin/imports/{sourceCode}
```

控制器只接收管理请求，不包含来源字段映射和数据库写入逻辑。

### 4.2 `SourceImportService`

该服务负责所有来源共用的导入行为：

- 从 `data/` 安全解析文件路径，阻止目录穿越；
- 检查文件存在、来源是否支持该扩展名以及文件大小；
- 只允许 `license_status = approved` 的请求执行；
- 计算 SHA-256 文件哈希和稳定导入指纹；
- 创建、完成并查询 `source_import_runs`；
- 调用来源对应的 Importer；
- 统一生成规范词形并提示可能重复的正式词条；
- 写入候选池并统计成功、拒绝和候选数量。

### 4.3 `CandidateImporter`

Importer 只负责来源专属解析：

```java
String sourceCode();
String sourceName();
String sourceUrl();
String parserVersion();
boolean supportsFileName(String fileName);
ImportedBatch parse(Path file);
```

新增来源不得在 `SourceImportService` 中增加来源判断分支，也不得直接写数据库。

---

## 5. 统一导入输出

每个 Importer 将来源记录转换为统一候选结构：

```text
sourceIndex       原文件中的记录位置
sourceRecordKey   来源记录稳定键；缺失时由系统生成
term              待编辑候选表达
definition        来源提供的释义；没有可信释义时为空
sourceUrl         来源核验地址
processingNote    起源、例句、风险提示和来源特有的最小元数据
```

Importer 不得把无法确认的内容伪装成正式释义。来源字段不能映射时，应丢弃、记录最小提示或明确留空，而不是为每个来源扩展候选表字段。

---

## 6. 已支持来源及字段映射

### 6.1 CHIME

| 项目 | 当前实现 |
|---|---|
| source code | `chime` |
| 文件格式 | `.json`，根节点必须为数组 |
| 解析器版本 | `chime-json-v1` |
| 来源地址 | `https://github.com/yuboxie/chime` |

字段映射：

| CHIME 字段 | 候选字段 |
|---|---|
| `meme` | `term_raw`，必填 |
| `meaning` | `definition_raw` |
| `origin` | `processing_note.origin`，最多 500 字符 |
| `examples` | `processing_note.examples`，最多三条、每条最多 300 字符 |
| `profanity` | `processing_note.profanity`，缺失时为 `false` |
| `offense` | `processing_note.offense`，缺失时为 `false` |
| `type_cn/type_en` | 来源分类提示 |

CHIME 是词条型数据，因此可以直接提供候选词形和释义草稿，但仍须人工编辑和审核。

### 6.2 Buzzword

| 属性 | 值 |
|---|---|
| 来源 code | `buzzword` |
| 解析器版本 | `buzzword-json` |
| 来源地址 | `https://github.com/SCUNLP/Buzzword` |

Buzzword 文件为 JSON 数组。仅作最小化映射：

| Buzzword 字段 | 候选字段 |
|---|---|
| `term` | `source_record_key`、`term_raw`，必填 |
| `ground_truth` | `definition_raw`，必填 |
| `examples` | `processing_note.examples`，最多三条、每条最多 300 字符 |
| `definition`、模型输出及评分字段 | 不导入 |

导入前按归一化词形查询正式词条和已有候选；任一存在时跳过该记录，避免 Buzzword 与 CHIME 或其他来源产生重复候选。

## 7. 导入执行流程

```text
1. 操作者将文件放入 data/
2. 页面选择来源，系统只展示该 Importer 支持的文件
3. 操作者填写来源版本、许可证状态和权利核验说明
4. SourceImportService 校验路径、格式、大小和许可证状态
5. 系统计算 file_hash 与 import_fingerprint
6. 创建 source_import_runs
7. 对应 Importer 解析来源记录
8. 系统统一归一化词形并查询可能重复的正式词条
9. 合格结果写入 candidate_entries.editing
10. 完成运行统计并展示错误摘要
11. 编辑在候选池补充或修正内容
12. 候选提交审核，批准后发布正式词条
```

导入操作不会创建正式词条，也不会自动提交审核。

---

## 8. 幂等、重复提示与统计

### 8.1 文件级幂等

导入指纹由以下内容生成：

```text
source_name + source_version + file_hash
```

相同指纹已有 `running`、`succeeded` 或 `partial_success` 运行时，系统直接返回已有运行；只有失败或取消运行可以创建递增 `attempt_no` 的新运行。

### 8.2 记录级幂等

候选使用以下唯一关系避免同一次文件导入重复写入：

```text
import_fingerprint + source_record_key
```

CHIME 没有稳定记录 ID 时，系统根据记录位置和规范词形生成键。

### 8.3 正式词条重复提示

V1.0 的导入候选固定使用简体中文语境。写入候选前，系统使用规范词形查询
`language_code = 'zh-CN'` 的正式词条，等价条件为：

```text
normalized_term + language_code
```

查询可能重复的正式词条并写入 `duplicate_meme_id`。这是编辑提示，不代表系统自动合并或覆盖正式词条。
当前候选结构不单独保存 `language_code`；未来支持多语言导入时，应在统一候选结构中增加
`languageCode`，并继续按 `normalized_term + language_code` 进行重复提示。

### 8.4 运行统计

每次运行记录：

```text
total_count       来源原始记录数
accepted_count    格式可接受的来源记录数
rejected_count    解析或持久化失败记录数
candidate_count   本次实际新增候选数
error_summary     最多保留前十条错误摘要
```

---

## 9. 异常处理与运行状态

运行状态：

```text
running
succeeded
partial_success
failed
cancelled
```

处理原则：

- 文件不存在、格式不受支持、文件超限或许可证未批准时，请求在创建运行前被拒绝；
- 文件整体无法解析时，运行标记为 `failed`；
- 单条记录无效时记录拒绝原因，继续处理其他记录；
- 同时存在成功和失败记录时标记为 `partial_success`；
- 运行中的异步导入允许管理员软停止；停止后不再领取新词条，当前正在处理的单条记录允许完成；
- 已停止任务标记为 `cancelled`，服务重启后不再继续，且不能通过失败词条重试恢复为 `running`；
- V1.0 不自动重试；管理员修正文件或核验信息后重新发起；
- 错误摘要只保存定位问题所需的最小信息，不保存完整原始数据。

---

## 10. 候选流程交接

导入完成后的候选状态统一为：

```text
editing
```

无论来源是 CHIME 还是人工录入，后续流程完全一致：

```text
editing → 编辑补充 → pending_review
                       ├── published
                       └── returned → 再次编辑和提交
```

审核中的候选禁止编辑。批准时由候选发布服务事务性写入正式词条和 `meme_revisions`，导入模块不参与审核决策。若 V1 AI 变体生成开关已开启，批准操作会在同一次发布快照中加入模型生成且经服务端校验的变体；详见 [V1 AI 变体生成](llm-variant-generation-v1.md)。

---

## 11. 新增来源的实现规范

接入新来源时只增加独立 Importer：

```text
1. 在 data-source-governance.md 登记并完成权利核验
2. 确认文件格式和真实字段样例
3. 实现 CandidateImporter
4. 明确 source code、来源名称、URL 和 parser version
5. 将来源字段保守映射为 ImportedCandidate
6. 为正常数据、缺失字段、错误记录和重复记录编写测试
7. 注册为 Spring 组件，自动进入 Importer 注册表
8. 更新本文的“已支持来源及字段映射”章节
```

不得采用以下方式：

- 在通用服务中持续增加 `if (source == ...)`；
- 为来源特有字段不断增加候选表列；
- 让 Importer 直接写正式词条或作出审核结论；
- 对无法确认的字段进行猜测性映射；
- 因文件可读取就默认认为来源已获得授权。

---

## 12. 当前限制与后续演进

V1.0 当前限制：

- 文件必须由操作者手工放入 `data/`；
- 导入在 Web/API 进程中同步执行；
- 没有远程下载、任务队列、定时调度和自动重试；
- 没有通用 `data_sources` 来源配置表；
- 来源质量需要通过候选编辑成本、重复率和审核采纳率人工评估。

只有出现明确需求后，才评估远程受控下载、异步 Worker、来源配置表、自动调度、AI 候选提炼或趋势采集。这些能力不属于当前导入器契约，也不应提前混入 V1.0 文档。

---

## 13. V1.0 验收条件

1. 管理页面能够列出已注册 Importer，并按来源筛选支持的文件；
2. CHIME JSON 可以映射词形、释义、起源、例句和风险提示；
3. 非 `approved` 许可证状态不能执行导入；
4. 相同来源版本和文件内容重复请求不会重复创建候选；
5. 单条错误不会阻断其他有效记录；
6. 每次运行可查看来源、文件、解析器版本、统计和错误摘要；
7. 所有产物只进入候选池，必须经过编辑和审核才能成为正式词条；
8. 新增来源不需要修改通用导入编排和数据库表结构。
