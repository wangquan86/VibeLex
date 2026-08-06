# VibeLex V3.2 部署说明

V3.2 包含不可原地修改的 Elasticsearch strict mapping 变更，发布顺序必须如下：

1. 保持 `VIBELEX_RECOMMENDATION_V3_ENABLED=false`，备份数据库并部署 `vibelex-3.2.0.jar`。
2. 启动应用，让 Flyway 执行 V12；确认三个冗余策略字段已删除。
3. 在管理页面“共享义项索引”执行全量重建，或调用 `POST /api/admin/search/index/rebuild`。接口会立即创建重建任务并返回 `id`，应用按增量任务 worker 分批写入带时间戳的新物理索引；通过 `GET /api/admin/search/index/rebuild/{id}` 查询进度，全部成功后原子切换 `vibelex_sense_current`。
   全量任务处于 `preparing` 或 `running` 时，增量同步仍正常入队但暂停消费；别名切换成功或全量失败后自动恢复消费，积压任务会写入当时生效的索引别名。
4. 检查重建报告中的 `cleanupFailures`。别名切换成功后，应用立即删除切换前由该别名指向的旧物理索引；删除失败的索引会保留并在报告中列出，必须人工确认后清理。没有挂在当前别名下的孤立索引不会自动删除。
5. 验证索引文档只包含正式词条的 active 义项，向量维度与 embedding 服务配置一致。
6. 回归 `POST /api/v2/recognitions`：已发布和已归档词条仍可识别，纯语义候选仍须锚定原文。
7. 部署并启动 `deploy/reranker` 中的 CPU Reranker，确认 `GET http://10.145.12.11:8082/health` 返回 `status=ok`。
8. 设置 `VIBELEX_RECOMMENDATION_V3_ENABLED=true`、`VIBELEX_RERANKER_ENABLED=true` 并重启应用，再验证 V3 正常结果、重排序结果、空结果、400、413 和 503 契约。

增量同步任务会检查别名指向索引的 mapping 元数据。全量重建完成前，任务不会向 V2 旧 mapping 写入新结构，也不会被误标为成功；任务会按现有退避规则重试，并可在管理页面重新入队。

关键环境变量：

```text
VIBELEX_ES_ENABLED=true
VIBELEX_ES_URIS=http://<elasticsearch-host>:9200/
VIBELEX_ES_INDEX_NAME=vibelex_sense
VIBELEX_ES_INDEX_ALIAS=vibelex_sense_current
VIBELEX_EMBEDDING_ENABLED=true
VIBELEX_EMBEDDING_ENDPOINT=<embedding-endpoint>
VIBELEX_EMBEDDING_MODEL_NAME=bge-large-zh
VIBELEX_SEARCH_REBUILD_WORKER_FIXED_DELAY_MILLIS=5000
VIBELEX_RECOMMENDATION_V3_ENABLED=false
VIBELEX_RERANKER_ENABLED=true
VIBELEX_RERANKER_ENDPOINT=http://10.145.12.11:8082
VIBELEX_RERANKER_CONNECT_TIMEOUT_MILLIS=1000
VIBELEX_RERANKER_REQUEST_TIMEOUT_MILLIS=10000
```

重排序候选数由请求的 `max_results` 决定，没有单独的固定候选数配置；不足请求数量时对实际候选全部重排。设置 `VIBELEX_RERANKER_ENABLED=false` 后保留原 RRF 排序。Reranker 不可用或返回异常时，当前请求自动回退原 RRF 排序。

推荐日志仅记录 request_id、上下文码点长度、候选数量、Reranker 是否成功和分段耗时，不记录上下文正文、候选正文、查询体、向量或被过滤候选。
