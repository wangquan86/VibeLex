# VibeLex

> **Context-Aware Internet Language Knowledge Base**  
> 面向网络梗、流行语、圈层表达与语境理解的语言知识库。

VibeLex 用于发现、整理、审核、发布和识别网络语言表达。系统同时维护词义、语境、变体、例句、来源证据和风险策略，并通过人工审核保证正式词条质量。

**当前版本：V3.2** · [发布说明](docs/versions/v3.2/release-notes.md) · [部署说明](docs/versions/v3.2/deployment.md) · [OpenAPI](docs/versions/v3.2/openapi.yaml)

## 1. 核心能力

- 管理网络梗、流行语、缩写、谐音、模板句式和圈层表达；
- 使用主词条、义项和变体表达一词多义、别名及不同写法；
- 通过词面规则、上下文规则、Elasticsearch 和 embedding 完成语境识别；
- 根据剧情、台词、事件或对话上下文推荐语义相关的已发布词条；
- 从人工录入、CHIME、Buzzword、波普词典和热梗百科发现候选词条；
- 对文件导入和网站爬取执行逐条判重、AI 信息补全、失败重试和任务追踪；
- 通过候选编辑、提交审核、批准发布或退回修改治理词条；
- 保存来源证据、风险策略和正式词条版本快照。

## 2. 处理流程

```text
人工录入 / 文件导入 / 网站爬取
                ↓
        归一化、判重与 AI 补全
                ↓
        candidate_entries.editing
                ↓ 提交审核
        candidate_entries.pending_review
                ├── 批准 → meme_entries.published
                └── 退回 → candidate_entries.returned → 重新编辑
```

文件导入和网站爬取只创建候选，不直接发布正式词条。审核中的候选禁止修改，正式词条的重要变更通过 `meme_revisions` 留存版本快照。

识别服务只查询已发布正式词条，不使用候选数据。V2 识别融合规则、Elasticsearch 词法召回和向量语义召回，并在外部服务异常时按配置降级。

## 3. 技术栈

| 分类 | 实现 |
|---|---|
| 后端 | Java 17、Spring Boot 4.0.7 |
| 数据访问 | MyBatis Spring Boot Starter、MySQL 8、Flyway |
| 检索 | Elasticsearch BM25 / IK、dense_vector kNN |
| 语义向量 | BGE embedding 服务 |
| 管理页面 | 静态 HTML、CSS、原生 JavaScript |
| 后台任务 | 应用内 Worker 与 Spring Scheduling，不依赖 Redis 或 MQ |
| 构建 | Maven，前端静态资源直接打入 Spring Boot JAR |

项目采用模块化单体结构。MySQL 是权威数据源，Elasticsearch 保存可重建的检索投影。

## 4. 版本概览

| 版本 | 主要内容 |
|---|---|
| V1.0 | 词条模型、候选审核、文件导入、规则识别和版本记录 |
| V2.0 | Elasticsearch 词法/语义混合召回、索引重建和增量同步 |
| V3.0 | 多来源网站爬取、检查点、逐条记录、重试和候选导入 |
| V3.1 | 热梗百科 AI 提取、文件导入任务化、软停止及时间字段完善 |
| V3.2 | 上下文词条推荐 API、共享义项索引和加权 RRF 融合 |

V3.1 中，波普词典和热梗百科分别使用 `scheduled-enabled` 控制定时同步。设为 `false` 时不会由 Cron 自动发起任务，但仍可在管理页面手动爬取。

## 5. 文档

历史版本文档发布后冻结；`docs/reference/` 保存持续维护的当前规范，`docs/versions/` 保存各版本设计和接口快照。

### 5.1 当前规范

- [数据库模型](docs/reference/database-schema.md)
- [归一化规范](docs/reference/normalization.md)
- [数据来源治理](docs/reference/data-source-governance.md)

### 5.2 V3.2

- [推荐 API 设计](docs/versions/v3.2/recommendation-api-design.md)
- [发布说明](docs/versions/v3.2/release-notes.md)
- [部署说明](docs/versions/v3.2/deployment.md)
- [OpenAPI](docs/versions/v3.2/openapi.yaml)

### 5.3 V3.1

- [发布说明](docs/versions/v3.1/release-notes.md)
- [热梗百科与 AI 信息提取](docs/versions/v3.1/regengbaike-ai-crawler.md)
- [文件导入任务化](docs/versions/v3.1/file-import-tasks.md)
- [部署说明](docs/versions/v3.1/deployment.md)
- [OpenAPI](docs/versions/v3.1/openapi.yaml)

### 5.4 历史版本

- V1.0：[系统架构](docs/versions/v1.0/system-architecture.md)、[数据集导入](docs/versions/v1.0/dataset-import.md)、[识别引擎](docs/versions/v1.0/recognition-engine.md)、[AI 变体生成](docs/versions/v1.0/llm-variant-generation.md)
- V2.0：[识别与 Elasticsearch](docs/versions/v2.0/recognition-elasticsearch.md)、[OpenAPI](docs/versions/v2.0/openapi.yaml)
- V3.0：[网站爬取与候选导入](docs/versions/v3.0/crawler-candidate-import.md)

## 6. 本地运行

环境要求：Java 17、Maven 3.9+、MySQL 8。Elasticsearch 和 embedding 服务用于 V2 词法及语义召回。

创建独立的本地数据库，避免连接服务器数据库：

```sql
CREATE DATABASE IF NOT EXISTS vibelex_local_db
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_0900_ai_ci;
```

复制环境变量模板并填写数据库、LLM、Elasticsearch 和 embedding 配置：

```powershell
Copy-Item .env.example .env
```

`.env` 已通过 `.gitignore` 排除。`application.yml` 会从当前工作目录读取该文件，操作系统环境变量和 systemd `EnvironmentFile` 可以覆盖同名配置。

测试、构建并启动：

```powershell
mvn test
mvn package
java -jar target/vibelex-3.2.0.jar
```

打开 `http://localhost:8080/` 使用管理页面。Flyway 在首次启动时自动执行数据库迁移；CHIME 和 Buzzword 文件应放在项目 `data/` 目录。

服务器部署、索引重建、V2 回归和推荐开关步骤见 [V3.2 部署说明](docs/versions/v3.2/deployment.md)。

## 7. License

本项目当前为内部项目，许可证待确定。
