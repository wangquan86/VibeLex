# VibeLex V3.1 发布说明

V3.1 在 V3.0 多来源爬取框架上增加热梗百科与 AI 信息提取，同时将 CHIME、Buzzword 文件导入升级为可观察、可停止的后台任务。

## 主要变化

### 热梗百科与爬虫

- 新增热梗百科 sitemap 枚举、数字 ID 检查点和增量同步；
- 新增普通 OpenAI 兼容 `/chat/completions` 客户端；
- LLM 配置拆分为 provider 和 scenario；
- 保存网页原始材料、AI 合法输出、provider、model 和处理器版本；
- 波普词典增加联网 AI 起源证据补充；
- 波普词典和热梗百科分别提供 `scheduled-enabled`，关闭定时任务不影响管理页面手动爬取；
- 爬取记录列表增加分页序号和爬取时间。

详细设计见 [热梗百科爬取与 AI 信息提取](regengbaike-ai-crawler.md)。

### 文件导入

- CHIME、Buzzword 改为“任务 + 逐条记录 + Worker”异步执行；
- 新增逐条处理状态、失败原因、AI 追溯信息和导入时间；
- 新增失败记录自动重试、单条或批量人工重试；
- 新增运行中任务软停止；
- 已停止任务不会在服务重启后继续，也不能恢复为运行中；
- 已停止或失败的相同文件允许创建递增 `attempt_no` 的新任务；
- 任务列表增加最近更新时间。

详细设计见 [文件导入任务化](file-import-tasks.md)。

### 管理页面

- 候选列表删除“重复词条”列，增加“进入候选时间”；
- 爬取记录增加全局分页序号和“爬取时间”；
- 文件导入任务增加“更新时间”；
- 文件导入逐条记录增加“导入时间”；
- 上述时间统一显示为 `yyyy-MM-dd HH:mm:ss`；
- 取消状态统一显示为“已停止”。

## 数据库迁移

V3.1 包含以下 Flyway 迁移：

| 版本 | 内容 |
|---|---|
| V8 | 热梗百科 AI 爬虫追溯字段 |
| V9 | 爬虫抽样验证批次 |
| V10 | 文件导入逐条记录、运行状态与统计字段 |
| V11 | 文件导入任务 `updated_at` |

应用连接空数据库时会自动执行 V1 至 V11。不要只删除业务表而保留 `flyway_schema_history`，否则 Flyway 会认为历史迁移已经执行。

当前完整表结构见 [数据库模型](../../reference/database-schema.md)。

## 配置变化

V3.1 通过项目根目录 `.env` 或操作系统环境变量提供外部服务配置。实际 `.env` 不进入 Git，仓库只保留 `.env.example`。主要变量包括：

```text
SPRING_DATASOURCE_URL
SPRING_DATASOURCE_USERNAME
SPRING_DATASOURCE_PASSWORD
VIBELEX_SEARCH_LLM_BASE_URL
VIBELEX_SEARCH_LLM_API_KEY
VIBELEX_SEARCH_LLM_MODEL
VIBELEX_GENERAL_LLM_BASE_URL
VIBELEX_GENERAL_LLM_API_KEY
VIBELEX_GENERAL_LLM_MODEL
VIBELEX_ES_URIS
VIBELEX_ES_INDEX_NAME
VIBELEX_ES_INDEX_ALIAS
VIBELEX_EMBEDDING_ENDPOINT
VIBELEX_EMBEDDING_MODEL_NAME
```

自动爬取默认建议关闭，手动爬取保持可用：

```text
--vibelex.crawling.enabled=true
--vibelex.crawling.popcidian.scheduled-enabled=false
--vibelex.crawling.regengbaike.scheduled-enabled=false
```

完整服务器配置见 [V3.1 部署说明](deployment.md)。

## 已知边界

- 文件导入停止为协作式软停止，已经发出的当前 AI 请求可能正常完成并产生 token 消耗；
- 文件导入不提供任意终态任务的原地重新启动，失败记录可以重试，取消任务需要重新发起导入；
- 网站爬取和文件导入仍运行在同一个应用进程中；
- 当前管理接口部署边界为受控内网 dev 环境，尚未提供生产级登录、鉴权和调用限流。

## 文档

- [V3.1 热梗百科爬取与 AI 信息提取](regengbaike-ai-crawler.md)
- [V3.1 文件导入任务化](file-import-tasks.md)
- [V3.1 部署说明](deployment.md)
- [V3.1 OpenAPI](openapi.yaml)
