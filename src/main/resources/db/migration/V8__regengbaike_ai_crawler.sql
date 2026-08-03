ALTER TABLE crawl_records
  ADD COLUMN source_payload MEDIUMTEXT NULL COMMENT '确定性抓取的原始结构化材料 JSON',
  ADD COLUMN processor_version VARCHAR(64) NULL COMMENT '内容处理器版本',
  ADD COLUMN ai_model VARCHAR(255) NULL COMMENT '实际使用的模型名称',
  ADD COLUMN ai_output MEDIUMTEXT NULL COMMENT '通过校验的 AI 结构化输出 JSON',
  ADD COLUMN fetched_at DATETIME(3) NULL,
  ADD COLUMN ai_processed_at DATETIME(3) NULL;
