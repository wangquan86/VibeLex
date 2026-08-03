# VibeLex V3.1 部署说明

本文适用于当前内网 dev 服务器：root 用户运行、systemd 管理、部署目录 `/opt/vibelex`、Java 17。

## 1. 目录结构

```text
/opt/vibelex/
├── vibelex-3.1.0.jar
├── .env
└── data/
```

`data/` 保存 CHIME、Buzzword 等本地导入文件。systemd 的 `WorkingDirectory` 固定为 `/opt/vibelex`，同时通过绝对路径设置数据目录，因此服务不依赖操作人员当前所在目录。

## 2. 环境变量

敏感信息不得写入 Git、README、`application.yml` 或 JAR。参考仓库中的 `.env.example` 创建仅 root 可读的 `/opt/vibelex/.env`：

```text
SPRING_DATASOURCE_URL=jdbc:mysql://<mysql-host>:3306/vibelex_db
SPRING_DATASOURCE_USERNAME=<mysql-user>
SPRING_DATASOURCE_PASSWORD=<mysql-password>
VIBELEX_SEARCH_LLM_API_KEY=<search-llm-key>
VIBELEX_SEARCH_LLM_BASE_URL=<search-llm-base-url>
VIBELEX_SEARCH_LLM_MODEL=<search-llm-model>
VIBELEX_GENERAL_LLM_API_KEY=<general-llm-key>
VIBELEX_GENERAL_LLM_BASE_URL=<general-llm-base-url>
VIBELEX_GENERAL_LLM_MODEL=<general-llm-model>
VIBELEX_ES_URIS=http://<elasticsearch-host>:9200/
VIBELEX_ES_INDEX_NAME=vibelex_sense_v2
VIBELEX_ES_INDEX_ALIAS=vibelex_sense_current
VIBELEX_EMBEDDING_ENDPOINT=<embedding-endpoint>
VIBELEX_EMBEDDING_MODEL_NAME=bge-large-zh
```

设置权限：

```bash
chown root:root /opt/vibelex/.env
chmod 600 /opt/vibelex/.env
```

本地开发必须使用独立数据库，例如 `vibelex_local_db`，禁止和服务器共同使用 `vibelex_db`。

## 3. systemd

仓库中的 [vibelex.service](../../../deploy/vibelex.service) 使用以下关键配置：

```ini
[Service]
User=root
WorkingDirectory=/opt/vibelex
EnvironmentFile=/opt/vibelex/.env
ExecStart=/opt/jdk-17.0.8.1+1/bin/java -Xms2g -Xmx4g -jar /opt/vibelex/vibelex-3.1.0.jar ...
```

安装或更新服务文件：

```bash
cp /opt/vibelex/vibelex.service /etc/systemd/system/vibelex.service
systemd-analyze verify /etc/systemd/system/vibelex.service
systemctl daemon-reload
systemctl enable vibelex
```

`enable` 只设置开机启动；`start` 启动服务；`enable --now` 同时完成两者。

## 4. 构建和上传

本地构建：

```powershell
mvn -q clean package
Get-FileHash .\target\vibelex-3.1.0.jar -Algorithm SHA256
```

上传时先使用临时文件名：

```text
/opt/vibelex/vibelex-3.1.0.jar.uploading
```

服务器停止服务并替换：

```bash
systemctl stop vibelex
mv /opt/vibelex/vibelex-3.1.0.jar /opt/vibelex/vibelex-3.1.0.jar.backup
mv /opt/vibelex/vibelex-3.1.0.jar.uploading /opt/vibelex/vibelex-3.1.0.jar
sha256sum /opt/vibelex/vibelex-3.1.0.jar
systemctl start vibelex
```

确认服务器 SHA-256 与本地一致，避免旧包、上传不完整或覆盖失败。

## 5. 数据库迁移

应用启动时自动执行 Flyway。查看本次启动日志：

```bash
journalctl -u vibelex --since "5 minutes ago" --no-pager
```

筛选迁移与错误：

```bash
journalctl -u vibelex --since "5 minutes ago" --no-pager \
  | grep -Ei 'flyway|migration|migrating|started|error|exception'
```

全新数据库应依次执行 V1 至 V11。若需要彻底重建 dev 数据库，应先停止服务器和本地应用，然后删除并重新创建整个数据库；不要保留旧的 `flyway_schema_history`。

## 6. 启停和日志

```bash
systemctl start vibelex
systemctl stop vibelex
systemctl restart vibelex
systemctl status vibelex --no-pager -l
```

查看最近 300 行日志：

```bash
journalctl -u vibelex -n 300 --no-pager
```

查看本次开机以来的日志：

```bash
journalctl -u vibelex -b --no-pager
```

实时跟踪只用于观察新日志：

```bash
journalctl -u vibelex -f
```

## 7. 网络检查

确认应用监听 8080：

```bash
ss -lntp | grep ':8080'
```

如需允许内网远程访问，添加 firewalld 规则：

```bash
firewall-cmd --permanent --zone=public --add-port=8080/tcp
firewall-cmd --reload
firewall-cmd --zone=public --query-port=8080/tcp
```

## 8. 定时任务策略

dev 环境允许手动爬取，但默认禁止 Cron 自动启动：

```text
--vibelex.crawling.enabled=true
--vibelex.crawling.popcidian.scheduled-enabled=false
--vibelex.crawling.regengbaike.scheduled-enabled=false
```

`vibelex.crawling.enabled=false` 会同时关闭爬虫能力，管理页面也无法手动启动，因此不用于当前 dev 部署。

## 9. 发布后验证

1. `systemctl status` 显示 `active (running)`；
2. Flyway 日志没有失败，`flyway_schema_history` 到达 V11；
3. 8080 正常监听并能从内网访问；
4. 管理页面显示 V3.1 新字段和“已停止”状态；
5. 自动爬取保持关闭，手动爬取可用；
6. 本地开发应用没有连接服务器的 `vibelex_db`。
