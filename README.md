# VibeLex

> **Context-Aware Internet Language Knowledge Base**  
> 面向网络梗、流行语、圈层表达与语境理解的语言知识库。

VibeLex 是一个用于收集、整理、审核、检索和识别网络语言表达的知识库系统。

它不仅记录“一个词是什么意思”，还关注该表达在什么语境下成立、有哪些别名和变体、是否存在多重含义、当前热度如何、是否具有风险，以及如何在真实文本中准确识别。

---

## 1. 项目目标

互联网语言变化快，网络梗、缩写、谐音、模板句式和圈层表达常常具有以下特点：

- 同一个词在不同上下文中含义不同；
- 同一个梗存在拼音、缩写、谐音、错别字等多种写法；
- 部分表达具有反讽、调侃、攻击或低俗风险；
- 热度会快速出现、增长、衰退和过时；
- 传统词典难以覆盖网络语境和圈层文化。

VibeLex 的目标是建立一套可持续维护的网络语言知识基础设施，为 AI 应用、内容平台、搜索系统、运营工具和审核系统提供可靠的梗词与流行语理解能力。

---

## 2. 核心能力

### 2.1 梗词与流行语管理

VibeLex 支持维护网络语言词条，包括但不限于：

- 谐音梗；
- 缩写梗；
- 数字梗；
- 模板句式；
- 网络流行语；
- 情绪表达；
- 反讽和阴阳怪气表达；
- 游戏、二次元、饭圈、职场等圈层术语；
- 外语借词和海外流行表达。

示例：

```text
破防
YYDS
尊嘟假嘟
你是懂xx的
treetree的
```

---



### 2.2 一梗多义

同一个词可能同时具有多个语义。

例如，“破防”可能表示：

```text
1. 情绪受到冲击，难以维持冷静；
2. 游戏中防御被突破；
3. 用于调侃某人情绪失控。
```

VibeLex 通过“主词条 + 义项”的方式管理多义表达，避免将不同语境混为一谈。

---



### 2.3 变体与别名识别

VibeLex 支持为词条维护多种语言变体，包括：

```text
别名
缩写
拼音
谐音
大小写变体
空格变体
常见错别字
繁体变体
衍生词形
```

例如：

```text
主词条：YYDS

可能变体：
yyds
Yyds
永远的神
永远滴神
```

---



### 2.4 上下文语义识别

VibeLex 不只进行简单关键词匹配，也支持根据上下文判断一个词是否真正以“梗义”出现。

匹配方式包括：

```text
精确词面匹配
标准化匹配
正则表达式匹配
拼音匹配
正向上下文关键词
负向上下文关键词
专名和字面义排除
语义相似度匹配
```

例如：

```text
“这薯片吃起来特别 treetree 的。”
```

可以识别为“形容食物酥脆”的谐音梗。

但：

```text
“tree 是英语中‘树’的意思。”
```

应被识别为英文单词字面义，而不是网络梗。

规则识别流水线详见 [识别引擎 V1 规格](docs/recognition-engine-v1.md)，Elasticsearch 词法/语义混合召回详见 [V2 识别与 ES 实现说明](docs/v2-recognition-and-es-design.md)。

---



### 2.5 风险与使用策略

部分网络表达可能包含粗俗、攻击、歧视、色情、暴力或其他敏感风险。

VibeLex 将“是否识别”和“是否推荐生成”分开管理。

例如，一个高风险词条可以配置为：

```text
允许系统识别：是
允许内部审核：是
允许普通用户查看：谨慎
允许 AI 主动生成：否
允许营销推荐：否
```

风险等级包括：

```text
none
low
medium
high
restricted
```

---



### 2.6 趋势与生命周期管理

词条生命周期与趋势变化使用不同字段表达。正式词条生命周期由 `status` 管理：

```text
published    已发布
disabled     已禁用
archived     历史归档
```

预留的 `trend_status` 只表示趋势变化：

```text
untracked    未跟踪
emerging     新出现
growing      快速增长
stable       长期稳定
declining    热度下降
```

V1 没有持续趋势数据源，正式词条默认使用 `untracked`，不展示、不人工维护，也不参与列表或识别排序。

---



### 2.7 来源与证据管理

每个词条可以关联多条内部证据，用于支持其词义、热度、起源、变体或风险判断。

来源功能层包括：

```text
dictionary      词典层
trend           趋势层
explanation     解释层
community       圈层社区层
dataset         数据集层
internal        内部编辑或产品观察
overseas        海外补充层
```

证据用于支撑编辑审核和质量追踪，而不是简单堆积外部内容。

---

### 2.8 数据来源与采集原则

VibeLex 通过编辑观察、用户投稿、已授权数据集、公开趋势信号、词典/解释资料和垂类社区等渠道发现候选表达，并坚持以下原则：

- 来源须可追溯；重要的词义、起源、趋势和风险结论应关联具体证据；
- 仅采集和留存完成发现、核验与趋势判断所必需的信息，默认保存结构化结论、必要短摘录、来源链接和观察时间，不以沉淀原始网络内容为目标；
- 自动化仅用于候选发现、去重、聚类和趋势预警，词义、风险和发布结论必须经人工审核；
- 公开可见不等于可批量抓取或再分发，来源接入须遵守授权、服务条款、robots 规则和隐私要求；
- 来源失效、规则变化或证据不足时，应触发复核、修订、停用或归档。

详细的来源分层、接入登记、留存策略与复核规则见 [数据来源与采集治理规范](docs/data-source-governance.md)。

管理页面明确区分三类信息：数据导入来源（CHIME、Buzzword、人工录入）、词条起源（编辑维护的传播说明及参考链接）和 AI变体参考来源（一次联网生成返回的词条级参考链接组）。AI变体参考来源不与单条变体一一对应。

V1 的 CHIME 与 Buzzword 文件由操作者手工放入项目 `data/` 目录。系统默认使用 `manual-local` 版本和 `approved/system` 权利核验信息，并在每次导入时计算实际文件哈希；默认值可在管理页面覆盖。

---



### 2.9 候选编辑、审核与正式发布

V1 将候选词条作为唯一的可编辑工作对象，不引入候选草稿或审核草稿：

```text
人工录入 / CHIME / Buzzword 导入
        ↓
candidate_entries.editing
        ↓ 提交审核并锁定编辑
candidate_entries.pending_review
        ├── 批准 → candidate_entries.published → meme_entries.published
        └── 退回 → candidate_entries.returned → 编辑后重新提交
```

人工录入和文件导入进入同一个候选池。审核中的候选禁止修改；退回后恢复编辑权限并保留审核意见。批准时在同一事务中生成或更新正式词条。

已发布正式词条可撤回至候选池继续编辑。再次批准时回写原正式词条，不创建重复词条；候选词条当前保存的变体列表会替换旧变体。

`entry_change_sets` 保留为通用正式词条变更能力，但 V1 候选审核流程不创建或使用 change set 草稿。

正式词条的重要发布或变更可以生成 `meme_revisions` 版本快照；候选池不维护版本快照。正式版本支持：

- 变更记录；
- 差异比对；
- 历史追溯；
- 版本回滚。

---



## 3. 数据库设计

VibeLex 当前使用 MySQL 8.0 作为主数据库。

V1 核心表共 11 张：


| 表名                     | 说明                      |
| ---------------------- | ----------------------- |
| `meme_entries`         | 主词条表，保存当前生效的词条、分类、热度和发布状态 |
| `meme_senses`          | 义项表，保存一个梗的多个释义、语境和标签    |
| `meme_variants`        | 变体表，保存别名、缩写、拼音、谐音和衍生词形  |
| `meme_examples`        | 例句表，保存正例、反例和边界示例        |
| `meme_match_rules`     | 匹配规则表，保存词面、正则、上下文和语义规则  |
| `meme_safety_policies` | 风险策略表，控制识别、展示、生成和推荐行为   |
| `meme_evidence`        | 证据表，保存来源、观察记录和判断依据      |
| `meme_revisions`       | 版本表，保存重要修改的完整快照         |
| `entry_change_sets`    | 保留的通用正式词条变更表；不参与 V1 候选审核流程 |
| `source_import_runs`   | 数据文件导入运行、许可证核验和统计记录      |
| `candidate_entries`    | 人工录入或数据文件导入的可编辑候选、审核状态与发布关联 |


表关系如下（子表可通过 `sense_id` 关联到具体义项；`sense_id` 为空表示适用于整个词条）：

```text
meme_entries
    │
    ├── meme_senses
    │       │
    │       ├── meme_variants
    │       ├── meme_examples
    │       ├── meme_match_rules
    │       └── meme_evidence
    │
    ├── meme_variants
    ├── meme_examples
    ├── meme_match_rules
    ├── meme_safety_policies（1 对 1）
    ├── meme_evidence
    └── meme_revisions

source_import_runs
    └── candidate_entries（文件导入候选；人工候选不关联导入运行）
            └── meme_entries（审核批准后通过 published_meme_id 关联）

entry_change_sets（保留的通用正式词条变更能力）
```

详细表结构、枚举字典和版本回滚语义见 [数据库设计文档](docs/database-design.md)。

词形归一化规则见 [归一化规范](docs/normalization-spec.md)。

识别流水线（召回、打分、消歧、冲突处理）见 [识别引擎 V1 规格](docs/recognition-engine-v1.md)。

V1 包含基础规则识别 API：变体与词面召回、上下文规则打分、义项消歧、重叠冲突处理和风险策略计算。语义向量召回不属于 V1。

---



## 4. 技术栈

当前实现：

```text
主开发语言：Java
应用框架：Spring Boot 4.0.7
JDK：17.0.17
构建工具：Apache Maven 3.9.11
数据访问：MyBatis Spring Boot Starter 4.0.1
管理页面：静态 HTML、CSS、原生 JavaScript ES Modules
前端构建：不引入 Node.js；静态资源位于 `src/main/resources/static/` 并直接打入 Spring Boot JAR
Maven 入口：项目根目录 `pom.xml`
数据库：MySQL 8.0
存储引擎：InnoDB
字符集：utf8mb4
排序规则：utf8mb4_0900_ai_ci
缓存与异步任务：不依赖 Redis/MQ；V2 使用应用内定时任务处理 ES 索引同步队列
身份标识：V1 使用固定操作者和 CurrentActorProvider；生产化后接入 IDSAAS
语义检索：BGE embedding 服务 + Elasticsearch dense_vector kNN
全文检索：Elasticsearch BM25 / IK 中文分词
```

初始化数据库建议：

```sql
CREATE DATABASE IF NOT EXISTS vibelex_db
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_0900_ai_ci;

USE vibelex_db;
```

---



## 5. 典型使用场景

VibeLex 可用于以下场景：

```text
AI 聊天机器人理解用户网络表达
短视频、直播与评论区内容理解
影视台词、弹幕和社交媒体文本分析
内容平台的热梗识别与趋势观察
品牌营销文案的流行语推荐
风险表达识别与审核辅助
游戏、二次元、饭圈、职场等圈层语言理解
智能搜索、语义召回和知识问答
```

---



## 6. 非目标

VibeLex 当前不定位为：

```text
通用百科全书
传统语言词典替代品
未经审核的网络内容聚合器
自动生成攻击性、歧视性或高风险表达的工具
单纯依赖词面匹配的敏感词系统
```

VibeLex 关注的是：

> 在尊重语境、来源、风险和语言变化的前提下，帮助系统理解网络语言。

---



## 7. 项目命名

```text
Project Name: VibeLex
Database Name: vibelex_db
```

项目名称含义：

```text
Vibe  = 网络语境、情绪、氛围、圈层感
Lex   = Lexicon，词库、词典、语言知识库
```

完整名称：

> **VibeLex — Context-Aware Internet Language Knowledge Base**

---



## 8. 实施范围与后续规划

### 8.1 V1.0 实施范围

```text
✅ 建立静态管理页面和 REST API
✅ 建立人工录入、候选编辑、分页/详情、直接提交审核和批量审核流程
✅ 建立候选池、通用导入编排以及 CHIME、Buzzword Importer（V1.0）
✅ 实现正式词条分页查询、详情和基础规则识别 API
✅ 实现正式词条版本记录和回滚基础能力
✅ 管理页面统一显示中文枚举名称，分页条数与翻页控件集中在表格底部
✅ 管理后台侧栏支持折叠，折叠后保留菜单图标
```

数据库中的 `trend_status` 与 `heat_score` 为后续趋势模块预留字段。V1 的趋势状态统一为 `untracked`，不展示、不人工维护，也不使用这些字段参与列表或识别排序。

### 8.2 V2.0 实施范围

```text
✅ 提供 POST /api/v2/recognitions 版本化识别接口
✅ 融合 V1 规则、Elasticsearch 词法和语义候选召回
✅ 复用 V1 归一化视图生成精确 Unicode 码点 offset
✅ 提供 ES 全量重建、别名切换、增量同步、失败重试和任务管理页面
✅ ES 或 embedding 异常时按配置使用仍可用的召回路径并标记 degraded
✅ 管理页面提供 V2 命中测试、请求状态和响应耗时
```

### 8.3 后续规划

```text
[ ] 建立自动候选发现与趋势采集流程
[ ] 支持更多数据集导入和词条批量导出
[ ] 建立词条质量评分机制
[ ] 建立 V2 固定回归样例集和准确率评测基线
[ ] 扩展更复杂的上下文解释
[ ] 建立热度更新和过期词条复审机制
[ ] 支持行业词包、私有词库和多租户能力
[ ] 根据数据量和查询压力评估 ES 扩容、鉴权和高可用部署
[ ] 接入 IDSAAS，实现生产级登录与接口权限控制
[ ] 当单级审核不再满足需求时，引入审核任务、多人审核和质量抽检
[ ] 当接入多个数据来源时，引入统一来源表、采集任务和 Connector 管理
[ ] 识别 API 对外开放时，区分公共接口与内部调试接口，并增加调用方与策略控制
```

---



## 9. 设计文档


| 文档                                                        | 说明                                             |
| --------------------------------------------------------- | ---------------------------------------------- |
| [database-design.md](docs/database-design.md)             | 数据库表结构、枚举字典、生命周期与版本回滚                          |
| [normalization-spec.md](docs/normalization-spec.md)       | `normalized_term` / `normalized_variant` 归一化规范 |
| [recognition-engine-v1.md](docs/recognition-engine-v1.md) | 识别引擎 V1：召回、打分、消歧与冲突处理                          |
| [data-source-governance.md](docs/data-source-governance.md) | 数据来源、采集与证据治理规范 |
| [dataset-import-v1.md](docs/dataset-import-v1.md) | V1.0 多来源数据集导入：Importer、字段映射、幂等与候选流程 |
| [llm-variant-generation-v1.md](docs/llm-variant-generation-v1.md) | V1 AI 变体生成：按场景配置、提示词文件与发布流程 |
| [system-architecture-v1.md](docs/system-architecture-v1.md) | 系统架构 V1：模块边界、数据流与实施路径 |
| [v2-recognition-and-es-design.md](docs/v2-recognition-and-es-design.md) | V2 识别与 Elasticsearch 混合召回实现基线 |
| [openapi-v2.yaml](docs/openapi-v2.yaml) | V2 识别、索引管理和同步任务 OpenAPI 契约 |


---



## 10. License

本项目当前为内部项目，许可证待确定。

---

## 11. 本地运行

后端代码位于 `src/main/java/`，管理页面位于 `src/main/resources/static/`。生产构建会将静态资源直接打入 Spring Boot 可执行 JAR，不需要额外的前端构建步骤。

```bash
mvn test
mvn package
```

启动前创建 MySQL 8 数据库：

```sql
CREATE DATABASE IF NOT EXISTS vibelex_db
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_0900_ai_ci;
```

当前开发环境的 MySQL 连接已直接写入 `src/main/resources/application.yml`：

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/vibelex_db
    username: root
    password: wjn2021$
```

从项目根目录直接启动：

```bash
java -jar target/vibelex-1.0.0-SNAPSHOT.jar
```

使用 IDEA 直接启动 Spring Boot 时，可在 Run Configuration 中保持 `Working directory` 为 `$ProjectFileDir$`，并在 `Program arguments` 中加入：

```text
--spring.web.resources.static-locations=file:./src/main/resources/static/,classpath:/static/ --spring.web.resources.cache.period=0
```

此开发配置会直接读取前端源码；修改 HTML、CSS 或 JavaScript 后只需刷新浏览器，无需重启后端。该参数仅用于本地开发，不应加入生产启动配置。

打开 `http://localhost:8080/` 使用管理页面。系统使用 `X-Actor-Id` 固定操作者标识，默认白名单为 `editor01`、`reviewer01`、`admin01`、`system`。Flyway 首次启动时依次创建核心业务表、候选审核流程和 V2 `index_sync_tasks` 索引同步任务表；迁移脚本位于 `src/main/resources/db/migration/`。

管理页面当前支持 CHIME 与 Buzzword JSON 导入。将文件放入 `data/` 后选择对应数据源导入。
