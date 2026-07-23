# VibeLex 数据库设计文档

**项目名称：** VibeLex  
**数据库名称：** `vibelex_db`  
**产品版本：** V1.0  
**文档修订：** 1.3  
**数据库：** MySQL 8.0+  
**存储引擎：** InnoDB  
**字符集：** utf8mb4  
**排序规则：** utf8mb4_0900_ai_ci  

---

## 1. 文档目的

VibeLex 是一个面向网络梗、流行语、圈层表达和语境理解的知识库系统。

本数据库用于存储和管理：

- 网络梗主词条；
- 一个梗的多个义项；
- 别名、缩写、拼音、谐音和错别字等变体；
- 正例、反例和边界例句；
- 词面、正则、上下文和语义匹配规则；
- 内容风险与产品使用策略；
- 词义、趋势、起源和风险的证据来源；
- 词条版本、审核记录和回滚快照。

相关设计文档：

| 文档 | 说明 |
|---|---|
| [normalization-spec.md](normalization-spec.md) | 词形归一化规范 |
| [recognition-engine-v1.md](recognition-engine-v1.md) | 识别引擎 V1 规格 |
| [data-source-governance.md](data-source-governance.md) | 数据来源、采集与证据治理规范 |

---

## 2. 设计原则

### 2.1 词条与义项分离

一个网络表达可能有多个意思，因此：

```text
主词条：负责词条身份和全局属性
义项：负责具体释义、语境和语义特征
```

例如：

```text
主词条：破防

义项 1：情绪受到冲击，难以维持冷静
义项 2：游戏中防御被突破
义项 3：调侃对方情绪失控
```

---

### 2.2 来源与证据合并

V1 不单独维护“来源站点配置表”。

来源名称、来源链接、来源功能层和证据用途，统一保存在 `meme_evidence` 表中。

优点：

```text
减少表数量；
避免维护低频来源配置；
每一条证据都能独立记录实际来源；
后续若来源规模扩大，可平滑拆分出 `data_sources` 表。
```

---

### 2.3 枚举使用 VARCHAR，不使用 MySQL ENUM

数据库中的枚举类字段使用 `VARCHAR`，由服务端代码、后台下拉选项或配置中心控制其合法值。

原因：

```text
避免新增分类时必须修改表结构；
方便灰度发布新枚举值；
方便跨数据库和跨服务兼容；
避免 MySQL ENUM 的维护成本。
```

---

### 2.4 布尔值字段规范

不使用已废弃的 `TINYINT(1)` 写法。

统一使用：

```sql
TINYINT UNSIGNED NOT NULL DEFAULT 0
```

或：

```sql
TINYINT UNSIGNED NOT NULL DEFAULT 1
```

并通过 `CHECK (... IN (0, 1))` 约束保证字段仅可取 `0` 或 `1`。

> `CHECK` 约束需要 MySQL 8.0.16 及以上版本才能被真正强制执行。

---

## 3. 数据库初始化

```sql
CREATE DATABASE IF NOT EXISTS vibelex_db
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_0900_ai_ci;

USE vibelex_db;
```

---

## 4. 核心表清单

| 序号 | 表名 | 中文名称 | 说明 |
|---:|---|---|---|
| 1 | `meme_entries` | 主词条表 | 保存当前生效的词条身份、分类、趋势和发布状态 |
| 2 | `meme_senses` | 义项表 | 保存一个词条的多个释义、语境、情绪和标签 |
| 3 | `meme_variants` | 变体表 | 保存别名、缩写、拼音、谐音、错别字等 |
| 4 | `meme_examples` | 例句表 | 保存正例、反例和边界示例 |
| 5 | `meme_match_rules` | 匹配规则表 | 保存精确、正则、上下文和语义匹配规则 |
| 6 | `meme_safety_policies` | 风险策略表 | 保存风险等级及识别、展示、生成、推荐策略 |
| 7 | `meme_evidence` | 证据表 | 保存来源信息和词义、趋势、起源等判断依据 |
| 8 | `meme_revisions` | 版本表 | 保存重要版本快照，支持审计和回滚 |
| 9 | `entry_change_sets` | 通用变更表 | 保留正式词条变更能力；不参与 V1 候选直接审核流程 |
| 10 | `source_import_runs` | 导入运行表 | 保存 CHIME 文件、许可证核验、运行状态与统计 |
| 11 | `candidate_entries` | 候选词条表 | 保存人工或数据文件导入候选、编辑内容、审核状态和正式词条关联 |

---

## 5. 实体关系说明

子表 `meme_variants`、`meme_examples`、`meme_match_rules`、`meme_evidence` 均通过 `meme_id` 关联主词条，并可选通过 `sense_id` 关联具体义项：

- `sense_id` 非空：记录仅适用于该义项；
- `sense_id` 为空：记录适用于该主词条的全部义项。

`meme_safety_policies` 与 `meme_revisions` 仅关联主词条。义项需要不同策略时，通过 `meme_senses.safety_policy_override` 覆盖词条默认策略。

```text
meme_entries
    │
    ├── meme_senses
    │       │
    │       ├── meme_examples
    │       ├── meme_match_rules
    │       ├── meme_variants
    │       └── meme_evidence
    │
    ├── meme_variants
    ├── meme_examples
    ├── meme_match_rules
    ├── meme_safety_policies
    ├── meme_evidence
    └── meme_revisions

source_import_runs
    └── candidate_entries（导入候选；人工候选不关联 source_import_runs）
            └── meme_entries（审核批准后通过 published_meme_id 关联）

entry_change_sets（保留的通用正式词条变更能力）
```

关系说明：

| 父表 | 子表 | 关系 | 删除规则 |
|---|---|---|---|
| `meme_entries` | `meme_senses` | 1 对多 | `RESTRICT`，正式词条禁止硬删除 |
| `meme_entries` | `meme_variants` | 1 对多 | `RESTRICT`，正式词条禁止硬删除 |
| `meme_entries` | `meme_examples` | 1 对多 | `RESTRICT`，正式词条禁止硬删除 |
| `meme_entries` | `meme_match_rules` | 1 对多 | `RESTRICT`，正式词条禁止硬删除 |
| `meme_entries` | `meme_safety_policies` | 1 对 1 | `RESTRICT`，正式词条禁止硬删除 |
| `meme_entries` | `meme_evidence` | 1 对多 | `RESTRICT`，正式词条禁止硬删除 |
| `meme_entries` | `meme_revisions` | 1 对多 | `RESTRICT`，历史版本不得随词条删除 |
| `meme_senses` | `meme_variants` | 1 对多（可选） | 删除义项时级联删除义项专属变体 |
| `meme_senses` | `meme_examples` | 1 对多（可选） | 删除义项时级联删除义项专属例句 |
| `meme_senses` | `meme_match_rules` | 1 对多（可选） | 删除义项时级联删除义项专属规则 |
| `meme_senses` | `meme_evidence` | 1 对多（可选） | 删除义项时级联删除义项专属证据 |
| `source_import_runs` | `candidate_entries` | 1 对多 | 已产生候选的运行不可物理删除 |
| `entry_change_sets` | `candidate_entries` | 1 对多（历史兼容，可选） | V1 候选直接审核不建立该关联；旧 change set 删除时候选关联置空 |
| `meme_entries` | `entry_change_sets` | 1 对多（可选） | 正式词条存在 change set 时不可物理删除 |

---

# 6. 表结构定义

---

## 6.1 主词条表：`meme_entries`

### 用途

保存当前生效梗词条的主身份信息、分类、来源摘要、趋势状态和发布状态。

一条记录代表一个“主梗”。

例如：

```text
破防
YYDS
treetree的
你是懂xx的
尊嘟假嘟
```

### 建表语句

```sql
CREATE TABLE meme_entries (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键，自增 ID',

    meme_code VARCHAR(64) NOT NULL COMMENT '梗词条唯一业务编号，例如 MEME_000001',

    canonical_term VARCHAR(255) NOT NULL COMMENT '主词条标准写法，例如 破防、YYDS、treetree的',

    normalized_term VARCHAR(255) NOT NULL COMMENT '标准化词形，用于去重和检索；建议统一大小写、空格、全半角和繁简体',

    language_code VARCHAR(16) NOT NULL DEFAULT 'zh-CN' COMMENT '语言代码；当前默认 zh-CN，预留多语言扩展',

    category VARCHAR(32) NOT NULL COMMENT '梗主分类，例如 homophone、abbreviation、template_phrase、slang',

    domain_tags JSON NULL COMMENT '领域或圈层标签数组，例如 ["美食","短视频","吃播"]',

    origin_summary VARCHAR(1000) NULL COMMENT '梗的文化起源简述；无法确认时为空，不建议填入猜测性内容',

    trend_status VARCHAR(32) NOT NULL DEFAULT 'untracked' COMMENT '趋势状态：untracked、emerging、growing、stable、declining',

    heat_score DECIMAL(5,2) NULL COMMENT '内部热度评分，建议范围为 0 至 100；暂未计算时可为空',

    status VARCHAR(32) NOT NULL DEFAULT 'published' COMMENT '正式词条状态：published、disabled、archived',

    current_version INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '当前快照版本号；首次正式发布和每次重要变更或回滚后递增',

    created_by VARCHAR(128) NULL COMMENT '创建者标识；V1 使用固定用户标识或 system',

    reviewed_by VARCHAR(128) NULL COMMENT '最后审核者标识',

    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '词条创建时间',

    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '词条最近修改时间',

    published_at DATETIME(3) NULL COMMENT '词条正式发布时间；未发布时为空',

    PRIMARY KEY (id),

    UNIQUE KEY uk_meme_code (meme_code),

    UNIQUE KEY uk_normalized_term_language (normalized_term, language_code),

    KEY idx_category_status (category, status),

    KEY idx_trend_status (trend_status),

    KEY idx_published_at (published_at)

) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
  COMMENT='梗词主表：保存词条身份、分类、趋势、审核和发布信息';
```

### 字段说明

| 字段 | 类型 | 是否必填 | 说明 |
|---|---|---:|---|
| `id` | BIGINT UNSIGNED | 是 | 数据库主键 |
| `meme_code` | VARCHAR(64) | 是 | 系统业务编号，如 `MEME_000001` |
| `canonical_term` | VARCHAR(255) | 是 | 主词条展示名称 |
| `normalized_term` | VARCHAR(255) | 是 | 用于统一检索和去重的标准化写法 |
| `language_code` | VARCHAR(16) | 是 | 当前默认 `zh-CN`，预留多语言能力 |
| `category` | VARCHAR(32) | 是 | 梗的主分类 |
| `domain_tags` | JSON | 否 | 圈层、领域和场景标签 |
| `origin_summary` | VARCHAR(1000) | 否 | 起源摘要 |
| `trend_status` | VARCHAR(32) | 是 | 预留的趋势变化状态；不承担词条生命周期 |
| `heat_score` | DECIMAL(5,2) | 否 | 0 至 100 的内部热度分 |
| `status` | VARCHAR(32) | 是 | 草稿、审核、发布、归档等状态 |
| `current_version` | INT UNSIGNED | 是 | 当前快照版本号；草稿初始为 0 |
| `created_by` | VARCHAR(128) | 否 | 创建者标识 |
| `reviewed_by` | VARCHAR(128) | 否 | 最后审核者标识 |
| `created_at` | DATETIME(3) | 是 | 创建时间 |
| `updated_at` | DATETIME(3) | 是 | 更新时间 |
| `published_at` | DATETIME(3) | 否 | 发布时间 |

---

## 6.2 义项表：`meme_senses`

### 用途

保存一个主梗的多个义项、适用语境、不适用语境、情绪特征和语义标签。

### 建表语句

```sql
CREATE TABLE meme_senses (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键，自增 ID',

    meme_id BIGINT UNSIGNED NOT NULL COMMENT '所属主词条 ID，对应 meme_entries.id',

    sense_no TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '义项序号；同一主词条内从 1 开始递增',

    short_definition VARCHAR(500) NOT NULL COMMENT '简短释义，用于搜索结果、接口和产品卡片展示',

    definition TEXT NOT NULL COMMENT '完整释义，由 AI 草拟并经编辑审核后形成',

    usage_context JSON NULL COMMENT '适用语境数组，例如 ["轻松聊天","短视频评论","游戏社区"]',

    non_usage_context JSON NULL COMMENT '不适用语境数组，用于降低误匹配，例如 ["技术文档","字面含义"]',

    semantic_tags JSON NULL COMMENT '语义标签数组，例如 ["情绪","无奈","调侃"]',

    emotion_tags JSON NULL COMMENT '情绪标签数组，例如 ["轻松","愤怒","嘲讽","无奈"]',

    safety_policy_override JSON NULL COMMENT '义项级风险策略覆盖；为空时完全继承词条级策略',

    polarity VARCHAR(32) NOT NULL DEFAULT 'neutral' COMMENT '情感倾向：positive、neutral_positive、neutral、neutral_negative、negative、mixed',

    formality VARCHAR(32) NOT NULL DEFAULT 'informal' COMMENT '正式程度：formal、neutral、informal、very_informal',

    status VARCHAR(32) NOT NULL DEFAULT 'active' COMMENT '义项状态：active、disabled、archived',

    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '义项创建时间',

    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '义项最近修改时间',

    PRIMARY KEY (id),

    CONSTRAINT fk_meme_senses_meme
        FOREIGN KEY (meme_id)
        REFERENCES meme_entries(id)
        ON DELETE RESTRICT
        ON UPDATE CASCADE,

    UNIQUE KEY uk_meme_sense_no (meme_id, sense_no),

    UNIQUE KEY uk_sense_meme (id, meme_id),

    KEY idx_meme_sense_status (meme_id, status)

) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
  COMMENT='梗词义项表：保存一个主梗的多个释义、语境、情绪和语义标签';
```

`safety_policy_override` 允许覆盖 `risk_level`、`risk_tags`、`detect_enabled`、`display_enabled`、`generate_enabled`、`recommend_enabled` 和 `moderation_policy`。未提供的字段继续使用词条级 `meme_safety_policies`。

---

## 6.3 变体表：`meme_variants`

### 用途

保存一个梗的别名、缩写、拼音、谐音、错别字、空格写法、大写写法和衍生词形。

### 建表语句

```sql
CREATE TABLE meme_variants (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键，自增 ID',

    meme_id BIGINT UNSIGNED NOT NULL COMMENT '所属主词条 ID，对应 meme_entries.id',

    sense_id BIGINT UNSIGNED NULL COMMENT '关联义项 ID；为空表示适用于该主词条全部义项',

    variant VARCHAR(255) NOT NULL COMMENT '变体原始写法，例如 tree tree的、yyds、破大防',

    normalized_variant VARCHAR(255) NOT NULL COMMENT '标准化后的变体写法，用于检索和匹配',

    variant_type VARCHAR(32) NOT NULL COMMENT '变体类型：alias、abbreviation、pinyin、homophone、typo_variant、spacing_variant、case_variant、traditional_variant、derived_form',

    confidence DECIMAL(5,4) NOT NULL DEFAULT 1.0000 COMMENT '该变体属于当前词条的置信度，建议范围为 0 至 1',

    source_method VARCHAR(32) NOT NULL DEFAULT 'editorial' COMMENT '变体来源方式：editorial、ai_suggested、rule_generated、source_observed',

    status VARCHAR(32) NOT NULL DEFAULT 'active' COMMENT '正式变体状态：active、disabled',

    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '变体创建时间',

    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '变体最近修改时间',

    PRIMARY KEY (id),

    CONSTRAINT fk_meme_variants_meme
        FOREIGN KEY (meme_id)
        REFERENCES meme_entries(id)
        ON DELETE RESTRICT
        ON UPDATE CASCADE,

    CONSTRAINT fk_meme_variants_sense
        FOREIGN KEY (sense_id, meme_id)
        REFERENCES meme_senses(id, meme_id)
        ON DELETE CASCADE
        ON UPDATE CASCADE,

    UNIQUE KEY uk_meme_normalized_variant_type (meme_id, normalized_variant, variant_type),

    KEY idx_normalized_variant (normalized_variant),

    KEY idx_variant_meme_status (meme_id, status),

    KEY idx_variant_type (variant_type)

) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
  COMMENT='梗词变体表：保存别名、缩写、拼音、谐音、错别字和衍生词形';
```

---

## 6.4 例句表：`meme_examples`

### 用途

保存用于展示、审核和模型评测的例句。

例句分为：

```text
positive：正例，明确使用了目标梗义
negative：反例，词面相似但不属于目标梗义
boundary：边界例，需要结合上下文判断
```

### 建表语句

```sql
CREATE TABLE meme_examples (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键，自增 ID',

    meme_id BIGINT UNSIGNED NOT NULL COMMENT '所属主词条 ID，对应 meme_entries.id',

    sense_id BIGINT UNSIGNED NULL COMMENT '关联义项 ID；为空表示适用于整个词条',

    example_text VARCHAR(2000) NOT NULL COMMENT '例句正文；建议使用编辑原创或审核通过的 AI 草稿',

    example_role VARCHAR(32) NOT NULL DEFAULT 'positive' COMMENT '例句角色：positive、negative、boundary',

    explanation VARCHAR(1000) NULL COMMENT '例句解释；反例或边界例应说明为何不属于或需谨慎判断',

    status VARCHAR(32) NOT NULL DEFAULT 'approved' COMMENT '正式例句状态：approved、disabled',

    created_by VARCHAR(128) NULL COMMENT '例句创建者标识',

    reviewed_by VARCHAR(128) NULL COMMENT '例句审核者标识',

    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '例句创建时间',

    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '例句最近修改时间',

    PRIMARY KEY (id),

    CONSTRAINT fk_meme_examples_meme
        FOREIGN KEY (meme_id)
        REFERENCES meme_entries(id)
        ON DELETE RESTRICT
        ON UPDATE CASCADE,

    CONSTRAINT fk_meme_examples_sense
        FOREIGN KEY (sense_id, meme_id)
        REFERENCES meme_senses(id, meme_id)
        ON DELETE CASCADE
        ON UPDATE CASCADE,

    KEY idx_example_meme_status (meme_id, status),

    KEY idx_example_sense (sense_id),

    KEY idx_example_role (example_role)

) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
  COMMENT='梗词例句表：保存正例、反例和边界示例，用于展示、评测和误匹配控制';
```

---

## 6.5 匹配规则表：`meme_match_rules`

### 用途

保存用于文本识别的规则，包括精确匹配、正则、拼音、正负上下文和语义阈值。

### 建表语句

```sql
CREATE TABLE meme_match_rules (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键，自增 ID',

    meme_id BIGINT UNSIGNED NOT NULL COMMENT '所属主词条 ID，对应 meme_entries.id',

    sense_id BIGINT UNSIGNED NULL COMMENT '关联义项 ID；为空表示规则适用于整个词条',

    rule_type VARCHAR(32) NOT NULL COMMENT '规则类型：exact_match、normalized_match、regex_match、pinyin_match、positive_context、negative_context、entity_exclusion、semantic_threshold',

    rule_value TEXT NOT NULL COMMENT '规则具体内容，例如词面、正则表达式、上下文关键词或实体名称',

    rule_config JSON NULL COMMENT '规则扩展配置，例如窗口大小、大小写敏感性、分词模式、词边界或模型名称',

    weight DECIMAL(6,4) NOT NULL DEFAULT 1.0000 COMMENT '规则权重，用于多规则综合判断；负向规则可使用负值',

    threshold DECIMAL(6,4) NULL COMMENT '规则阈值，例如语义匹配最低相似度 0.8200',

    priority INT NOT NULL DEFAULT 100 COMMENT '规则优先级，数值越小越优先执行',

    enabled TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '规则是否启用：0=停用，1=启用',

    created_by VARCHAR(128) NULL COMMENT '规则创建者标识',

    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '规则创建时间',

    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '规则最近修改时间',

    PRIMARY KEY (id),

    CONSTRAINT chk_match_rule_enabled
        CHECK (enabled IN (0, 1)),

    CONSTRAINT fk_meme_match_rules_meme
        FOREIGN KEY (meme_id)
        REFERENCES meme_entries(id)
        ON DELETE RESTRICT
        ON UPDATE CASCADE,

    CONSTRAINT fk_meme_match_rules_sense
        FOREIGN KEY (sense_id, meme_id)
        REFERENCES meme_senses(id, meme_id)
        ON DELETE CASCADE
        ON UPDATE CASCADE,

    KEY idx_rule_meme_enabled (meme_id, enabled),

    KEY idx_rule_sense_enabled (sense_id, enabled),

    KEY idx_rule_type_enabled (rule_type, enabled),

    KEY idx_rule_priority (priority)

) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
  COMMENT='梗词匹配规则表：保存精确、正则、拼音、上下文和语义匹配规则';
```

---

## 6.6 风险策略表：`meme_safety_policies`

### 用途

保存词条的安全风险和产品使用策略。

其中，“识别”“展示”“生成”“推荐”四类能力必须独立控制。

例如，高风险词条可以：

```text
允许识别：是
允许审核：是
允许展示：否
允许生成：否
允许推荐：否
```

### 建表语句

```sql
CREATE TABLE meme_safety_policies (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键，自增 ID',

    meme_id BIGINT UNSIGNED NOT NULL COMMENT '所属主词条 ID，对应 meme_entries.id',

    profanity TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '是否含粗俗、脏话或低俗表达：0=否，1=是',

    offense TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '是否可能构成冒犯、人身攻击或贬损：0=否，1=是',

    risk_tags JSON NULL COMMENT '额外风险标签数组，例如 ["sexual","violence","discrimination","high_ambiguity"]',

    risk_level VARCHAR(16) NOT NULL DEFAULT 'low' COMMENT '风险等级：none、low、medium、high、restricted',

    detect_enabled TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '是否允许系统识别该梗：0=否，1=是',

    display_enabled TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '是否允许向普通用户展示该梗解释：0=否，1=是',

    generate_enabled TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '是否允许 AI 主动生成或使用该梗：0=否，1=是',

    recommend_enabled TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '是否允许向品牌、运营或用户推荐该梗：0=否，1=是',

    moderation_policy VARCHAR(32) NOT NULL DEFAULT 'normal' COMMENT '处理策略：normal、log_only、manual_review、block、restricted',

    notes VARCHAR(2000) NULL COMMENT '风险审核备注、限制原因或处理建议',

    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '风险策略创建时间',

    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '风险策略最近修改时间',

    PRIMARY KEY (id),

    CONSTRAINT chk_safety_profanity
        CHECK (profanity IN (0, 1)),

    CONSTRAINT chk_safety_offense
        CHECK (offense IN (0, 1)),

    CONSTRAINT chk_safety_detect_enabled
        CHECK (detect_enabled IN (0, 1)),

    CONSTRAINT chk_safety_display_enabled
        CHECK (display_enabled IN (0, 1)),

    CONSTRAINT chk_safety_generate_enabled
        CHECK (generate_enabled IN (0, 1)),

    CONSTRAINT chk_safety_recommend_enabled
        CHECK (recommend_enabled IN (0, 1)),

    CONSTRAINT fk_meme_safety_policies_meme
        FOREIGN KEY (meme_id)
        REFERENCES meme_entries(id)
        ON DELETE RESTRICT
        ON UPDATE CASCADE,

    UNIQUE KEY uk_safety_policy_meme (meme_id),

    KEY idx_safety_risk_level (risk_level),

    KEY idx_safety_generate_recommend (generate_enabled, recommend_enabled)

) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
  COMMENT='梗词风险策略表：控制梗词的识别、展示、AI生成、推荐和审核策略';
```

---

## 6.7 证据表：`meme_evidence`

### 用途

记录支撑词条的词义、热度、起源、变体、风险和审核结论的依据。

V1 中，来源信息直接在本表维护。

### 建表语句

```sql
CREATE TABLE meme_evidence (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键，自增 ID',

    meme_id BIGINT UNSIGNED NOT NULL COMMENT '所属主词条 ID，对应 meme_entries.id',

    sense_id BIGINT UNSIGNED NULL COMMENT '关联义项 ID；为空表示证据适用于整个词条',

    source_layer VARCHAR(32) NOT NULL COMMENT '来源功能层：dictionary、trend、explanation、community、dataset、internal、overseas',

    source_name VARCHAR(255) NOT NULL COMMENT '来源名称，例如 某梗词典、某视频平台趋势页、内部编辑观察',

    source_url VARCHAR(2048) NULL COMMENT '具体来源页面、话题页、趋势页或数据记录链接',

    evidence_role VARCHAR(32) NOT NULL COMMENT '证据用途：discovery、meaning、trend、origin、variant、risk、review',

    evidence_note VARCHAR(1000) NULL COMMENT '内部证据摘要；建议记录结论和必要上下文，不保存大量原始文本',

    observed_at DATETIME(3) NULL COMMENT '发现或核验该证据的时间',

    confidence DECIMAL(5,4) NULL COMMENT '该证据对词条判断的支持置信度，建议范围为 0 至 1',

    status VARCHAR(32) NOT NULL DEFAULT 'active' COMMENT '证据状态：active、invalid、removed',

    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '证据记录创建时间',

    PRIMARY KEY (id),

    CONSTRAINT fk_meme_evidence_meme
        FOREIGN KEY (meme_id)
        REFERENCES meme_entries(id)
        ON DELETE RESTRICT
        ON UPDATE CASCADE,

    CONSTRAINT fk_meme_evidence_sense
        FOREIGN KEY (sense_id, meme_id)
        REFERENCES meme_senses(id, meme_id)
        ON DELETE CASCADE
        ON UPDATE CASCADE,

    KEY idx_evidence_meme_role (meme_id, evidence_role),

    KEY idx_evidence_source_layer (source_layer),

    KEY idx_evidence_observed_at (observed_at)

) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
  COMMENT='梗词证据表：记录梗词发现、释义、热度、起源、变体和风险判断依据';
```

---

## 6.8 版本表：`meme_revisions`

### 用途

保存每次重要修改、发布、停用、归档和回滚时的完整快照。

**权威数据源约定：**

- 运行时读写以各业务表（`meme_entries` 及其子表）为权威数据源；
- `meme_revisions.snapshot` 用于审计、差异比对和回滚恢复，不作为日常查询来源。

回滚流程、快照 JSON 结构和并发控制见 [§9.4 版本快照与回滚语义](#94-版本快照与回滚语义)。

### 建表语句

```sql
CREATE TABLE meme_revisions (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键，自增 ID',

    meme_id BIGINT UNSIGNED NOT NULL COMMENT '所属主词条 ID，对应 meme_entries.id',

    version INT UNSIGNED NOT NULL COMMENT '版本号，应与 meme_entries.current_version 对应',

    change_type VARCHAR(32) NOT NULL COMMENT '变更类型：create、update、publish、disable、archive、rollback',

    change_summary VARCHAR(1000) NULL COMMENT '本次变更摘要，例如 新增别名、修改释义、调整风险等级',

    snapshot JSON NOT NULL COMMENT '该版本完整快照，建议包含主词条、义项、变体、例句、规则和风险策略',

    changed_by VARCHAR(128) NULL COMMENT '修改者标识',

    reviewed_by VARCHAR(128) NULL COMMENT '版本审核者标识',

    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '版本记录创建时间',

    PRIMARY KEY (id),

    CONSTRAINT fk_meme_revisions_meme
        FOREIGN KEY (meme_id)
        REFERENCES meme_entries(id)
        ON DELETE RESTRICT
        ON UPDATE CASCADE,

    UNIQUE KEY uk_meme_version (meme_id, version),

    KEY idx_revision_meme_created (meme_id, created_at),

    KEY idx_revision_change_type (change_type)

) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
  COMMENT='梗词版本表：保存重要版本快照，支持审计、比对和回滚';
```

---

## 6.9 保留的通用变更表：`entry_change_sets`

### 用途

该表由 V1 初始迁移创建，保留通用的正式词条变更数据结构、后端接口和历史兼容能力。当前候选页面与候选审核 API 不使用该表：候选内容直接保存在 `candidate_entries`，提交后锁定，批准时由候选服务事务性写入正式词条。不要基于此表再引入候选草稿或审核草稿概念。

```sql
CREATE TABLE entry_change_sets (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    meme_id BIGINT UNSIGNED NULL COMMENT '目标正式词条；新建词条时为空',
    change_type VARCHAR(32) NOT NULL COMMENT 'create、update',
    base_version INT UNSIGNED NULL COMMENT '开始编辑时的正式版本；新建词条时为空',
    proposed_snapshot JSON NOT NULL COMMENT '待审核的完整词条快照',
    status VARCHAR(32) NOT NULL DEFAULT 'draft' COMMENT 'draft、pending_review、approved、rejected',
    change_summary VARCHAR(1000) NULL,
    created_by VARCHAR(128) NOT NULL,
    submitted_by VARCHAR(128) NULL,
    submitted_at DATETIME(3) NULL,
    reviewed_by VARCHAR(128) NULL,
    reviewed_at DATETIME(3) NULL,
    review_comment VARCHAR(2000) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    CONSTRAINT fk_change_set_meme
        FOREIGN KEY (meme_id) REFERENCES meme_entries(id)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    KEY idx_change_set_status (status, submitted_at),
    KEY idx_change_set_meme_status (meme_id, status)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
  COMMENT='待审核变更：隔离未生效内容与正式词条';
```

若后续启用通用正式词条变更能力，该表预留的状态流转为：

```text
draft → pending_review → approved
                     └→ rejected → draft
```

上述预留状态不代表候选池状态；候选池使用 `editing → pending_review → published`，退回时使用 `returned`。V1 当前只对候选直接审核流程提供页面和 API。

---

## 6.10 导入运行表：`source_import_runs`

### 用途

保存一次手工数据文件导入的来源、权利核验、文件身份、运行状态、解析器版本和统计信息。V1.0 通过独立 Importer 支持 CHIME，不单独维护通用来源表。

```sql
CREATE TABLE source_import_runs (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    source_name VARCHAR(255) NOT NULL DEFAULT 'CHIME',
    source_url VARCHAR(2048) NOT NULL,
    source_version VARCHAR(255) NOT NULL DEFAULT 'manual-local' COMMENT '提交号、发布版本或下载日期；未提供时使用 manual-local',
    source_commit VARCHAR(128) NULL,
    file_name VARCHAR(512) NOT NULL,
    file_hash VARCHAR(128) NOT NULL COMMENT '文件内容哈希',
    import_fingerprint CHAR(64) NOT NULL COMMENT 'source_name、source_version 和 file_hash 的稳定指纹',
    attempt_no INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '同一指纹失败后的重试序号',
    parser_version VARCHAR(64) NOT NULL,
    license_status VARCHAR(32) NOT NULL DEFAULT 'approved' COMMENT 'approved、rejected、expired；V1 手工文件默认 approved',
    license_snapshot TEXT NULL COMMENT '许可证文本、摘要或受控快照引用；V1 可使用系统默认说明',
    upstream_rights_note VARCHAR(2000) NOT NULL DEFAULT 'V1 manual CHIME import approved' COMMENT '上游数据权利核验结论',
    license_checked_by VARCHAR(128) NOT NULL DEFAULT 'system',
    license_checked_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    status VARCHAR(32) NOT NULL DEFAULT 'running' COMMENT 'running、succeeded、partial_success、failed、cancelled',
    total_count INT UNSIGNED NOT NULL DEFAULT 0,
    accepted_count INT UNSIGNED NOT NULL DEFAULT 0,
    rejected_count INT UNSIGNED NOT NULL DEFAULT 0,
    candidate_count INT UNSIGNED NOT NULL DEFAULT 0,
    error_summary VARCHAR(2000) NULL,
    initiated_by VARCHAR(128) NOT NULL,
    started_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    finished_at DATETIME(3) NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uk_import_id_fingerprint (id, import_fingerprint),
    UNIQUE KEY uk_import_attempt (import_fingerprint, attempt_no),
    KEY idx_import_fingerprint (import_fingerprint, status),
    KEY idx_import_status_started (status, started_at),
    KEY idx_import_license_status (license_status)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
  COMMENT='多来源手工导入运行、许可证门禁与统计';
```

CHIME 文件由操作者手工放入项目 `data/` 目录。V1 对手工提供的 CHIME 文件默认通过版本、格式样例和权利核验：未提供版本时使用 `manual-local`，许可证状态默认 `approved`，核验者默认 `system`。系统仍计算并保存实际文件哈希，Importer 在运行时检测文件格式和字段。

只有同时满足以下条件才允许开始解析和候选写入：

```text
license_status = approved
source_version、file_hash、license_checked_by、license_checked_at 均已填写
```

---

## 6.11 候选词条表：`candidate_entries`

### 用途

保存人工录入或数据文件导入后的候选记录。候选是唯一可编辑的工作对象，不创建候选草稿；提交后锁定，审核批准后直接生成或更新正式词条。

```sql
CREATE TABLE candidate_entries (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    import_run_id BIGINT UNSIGNED NULL COMMENT '文件导入候选关联运行；人工录入为空',
    import_fingerprint CHAR(64) NULL COMMENT '文件导入候选的指纹；人工录入为空',
    source_record_key VARCHAR(255) NOT NULL,
    term_raw VARCHAR(255) NOT NULL,
    normalized_term VARCHAR(255) NOT NULL,
    definition_raw TEXT NULL,
    source_url VARCHAR(2048) NULL,
    parser_version VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'editing' COMMENT 'editing、pending_review、returned、published',
    duplicate_meme_id BIGINT UNSIGNED NULL COMMENT '可能重复的正式词条',
    change_set_id BIGINT UNSIGNED NULL COMMENT '历史兼容字段；V1 候选直接审核不使用',
    processing_note MEDIUMTEXT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    CONSTRAINT fk_candidate_import_run
        FOREIGN KEY (import_run_id, import_fingerprint)
        REFERENCES source_import_runs(id, import_fingerprint)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT fk_candidate_duplicate_meme
        FOREIGN KEY (duplicate_meme_id) REFERENCES meme_entries(id)
        ON DELETE SET NULL ON UPDATE CASCADE,
    CONSTRAINT fk_candidate_change_set
        FOREIGN KEY (change_set_id) REFERENCES entry_change_sets(id)
        ON DELETE SET NULL ON UPDATE CASCADE,
    UNIQUE KEY uk_candidate_source_record (import_fingerprint, source_record_key),
    KEY idx_candidate_status (status, created_at),
    KEY idx_candidate_normalized (normalized_term)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
  COMMENT='人工或 CHIME 候选：可编辑、提交审核、退回修改或审核发布';
```

V2 在此基础上增加 `source_type`、`created_by`、`submitted_by`、`submitted_at`、`review_base_version`、`reviewed_by`、`reviewed_at`、`review_comment` 和 `published_meme_id`。`import_run_id` 与 `import_fingerprint` 允许为空，以支持人工录入。V4 将 `processing_note` 扩展为 `MEDIUMTEXT`，用于保存候选编辑内容，例如起源说明、例句、变体及 AI变体参考来源。详见相应 Flyway 迁移。

---

# 7. 枚举字典

## 7.1 `language_code`：语言代码

当前 V1 默认只使用：

```text
zh-CN    简体中文互联网语境
```

预留值：

```text
zh-TW    繁体中文 / 台湾语境
zh-HK    繁体中文 / 香港语境
en       英文
ja       日文
ko       韩文
mixed    混合语言表达
```

---

## 7.2 `category`：梗主分类

```text
homophone          谐音梗
abbreviation       缩写梗
number_code        数字梗
template_phrase    模板句式
slang              网络流行语
emotion_expression 情绪表达梗
sarcasm            反讽、阴阳怪气表达
foreign_term       外语借词或外来梗
fandom_term        饭圈、娱乐圈表达
game_term          游戏、电竞梗
acg_term           二次元、动漫梗
livestream_term    直播、短视频梗
workplace_term     职场、互联网表达
other              其他
```

---

## 7.3 `trend_status`：热度状态

`trend_status` 与 `heat_score` 作为未来趋势模块的数据库预留字段保留。当前 V1 没有持续、稳定且获授权的趋势数据源，不计算真实热度，不在管理页面展示，也不参与正式词条排序或识别结果排序。发布流程统一写入 `untracked`，明确表示系统尚未跟踪该词条趋势。

未来接入持续的聚合观察数据后，趋势状态可按统一计算规则使用：

```text
untracked    未跟踪；V1 默认值
emerging     新出现，证据和热度仍需观察
growing      正在快速传播
stable       长期稳定常用
declining    热度下降
```

`archived` 和 `obsolete` 不属于趋势变化状态。历史保留、停用等生命周期由主词条 `status` 表达。

---

## 7.4 主词条 `status`

```text
published       已发布
disabled        暂停使用
archived        历史归档
```

候选的编辑中、审核中和已退回状态属于 `candidate_entries`；正式词条表只保存批准后的生效内容。候选池不维护版本快照，正式词条的重要发布仍写入 `meme_revisions`。

---

## 7.5 义项 `status`

```text
active       正常启用
disabled     暂停使用，不参与默认识别
archived     历史保留，不建议主动使用
```

---

## 7.6 `polarity`：情感倾向

```text
positive          正向
neutral_positive  轻度正向
neutral           中性
neutral_negative  轻度负向
negative          负向
mixed             复杂或混合情绪
```

---

## 7.7 `formality`：正式程度

```text
formal         正式表达
neutral        中性表达
informal       非正式网络表达
very_informal  强口语化、强圈层化或强玩梗表达
```

---

## 7.8 `variant_type`：变体类型

```text
alias                别名
abbreviation         缩写
pinyin               拼音写法
homophone            谐音写法
typo_variant         常见错别字或输入法误写
spacing_variant      空格、连字符或分隔符变体
case_variant         英文字母大小写变体
traditional_variant  繁体写法
derived_form         衍生词形
```

---

## 7.9 `source_method`：变体产生方式

```text
editorial        编辑人工添加
ai_suggested     AI 建议，待审核
rule_generated   根据规则自动生成
source_observed  从来源或语料中观察到
```

---

## 7.10 `example_role`：例句类型

```text
positive    正例，明确使用目标梗义
negative    反例，词面相似但不属于目标梗义
boundary    边界例，需要结合上下文判断
```

---

## 7.11 `rule_type`：匹配规则类型

```text
exact_match         精确词面匹配
normalized_match    标准化词面匹配
regex_match         正则表达式匹配
pinyin_match        拼音匹配
positive_context    正向上下文关键词
negative_context    负向上下文关键词
entity_exclusion    专名、字面义或实体排除规则
semantic_threshold  语义相似度阈值规则
```

---

## 7.12 `risk_level`：风险等级

```text
none          无明显风险
low           低风险
medium        中风险，需要结合上下文
high          高风险，不建议主动生成或推荐
restricted    受限，仅用于内部识别、审核或特定场景
```

---

## 7.13 `risk_tags`：风险标签

`risk_tags` 为 JSON 数组，可同时填写多个值。

```text
profanity          粗俗、脏话或低俗表达
insult             人身攻击、贬损、嘲讽风险
sexual             性暗示、色情或成人内容
violence           暴力、威胁、伤害表达
discrimination     性别、地域、职业、种族等歧视风险
political          政治敏感风险
illegal            违法、灰产、诈骗、毒品等风险
minor_sensitive    不适合未成年人
copyright_sensitive 作品、角色、台词或素材依赖风险
high_ambiguity     多义性强，容易误匹配
```

示例：

```json
["insult", "high_ambiguity"]
```

---

## 7.14 `moderation_policy`：处理策略

```text
normal         正常处理
log_only       仅记录，不进行拦截
manual_review  需要人工审核
block          拦截、不展示或不生成
restricted     限制在指定内部场景使用
```

---

## 7.15 `source_layer`：来源功能层

```text
dictionary      词典层，用于发现词条和参考释义
trend           趋势层，用于判断热度和传播情况
explanation     解释层，用于核验语义和背景
community       圈层社区层，用于发现垂类表达
dataset         数据集层，用于样本、评测和内部语料
internal        内部编辑、产品或模型观察
overseas        海外补充层，用于外来梗和跨语言表达
```

---

## 7.16 `evidence_role`：证据作用

```text
discovery       用于发现候选词
meaning         用于核验词义
trend           用于核验热度和传播
origin          用于核验起源
variant         用于核验别名或变体
risk            用于核验风险
review          用于记录编辑审核依据
```

---

## 7.17 `change_type`：版本变更类型

```text
create      创建首个正式版本
update      更新释义、变体、规则、例句或证据
publish     正式发布
disable     暂停使用
archive     历史归档
rollback    回滚至历史版本
```

---

# 8. 词条生命周期

候选审核状态流转：

```text
editing
  ↓ 提交并锁定编辑
pending_review
  ├── 批准 → published
  └── 退回 → returned → 编辑后重新提交
```

正式词条状态流转：

```text
published
  ├── disabled
  └── archived
```

正式词条一经创建便禁止硬删除。下线使用 `disabled`，历史保留使用 `archived`；服务层不提供正式词条 DELETE API，数据库外键使用 `RESTRICT` 防止义项、证据和版本历史被级联删除。候选清理策略不属于当前 V1 页面能力，后续按实际数据治理需求确定。

正式词条说明：

| 状态 | 是否默认检索 | 是否参与识别 | 是否允许推荐 |
|---|---:|---:|---:|
| `published` | 是 | 是 | 根据风险策略决定 |
| `disabled` | 否 | 默认否 | 否 |
| `archived` | 可选 | 可用于历史内容识别 | 否 |

---

# 9. 数据写入建议

## 9.1 从候选发布词条时

V1 按以下顺序处理：

```text
1. 人工录入或文件导入创建 candidate_entries.editing；
2. 编辑直接修改候选内容；
3. 提交时将候选改为 pending_review，并记录提交人与基础正式版本；
4. 审核期间禁止修改候选；
5. 批准后在同一事务中创建或更新 meme_entries 及其子表；
6. 生成新的 meme_revisions 正式版本；
7. 将候选改为 published，并写入 published_meme_id；
8. 退回时将候选改为 returned，记录审核意见并恢复编辑权限。
```

候选发布流程不创建 `entry_change_sets`。正式词条后续独立变更仍可使用保留的通用 change set 能力。

---

## 9.2 词条发布最低要求

建议一个词条满足以下条件后才允许发布：

```text
必须有主词条名称；
必须有至少一个 active 义项；
必须有完整释义；
必须配置基础风险策略；
必须至少存在一条词面匹配或变体记录；
必须有至少一条内部审核或来源证据；
必须生成第一个版本快照。
```

---

## 9.3 词条更新规则

以下修改建议生成新版本：

```text
修改主词条名称；
修改义项释义；
新增、删除或停用义项；
新增或删除重要变体；
修改匹配规则；
调整风险等级；
修改生成、展示或推荐策略；
发布、停用、归档或回滚词条。
```

仅修改编辑备注、修正排版等非语义变更，可视业务需要决定是否生成版本。

---

## 9.4 版本快照与回滚语义

### 9.4.1 快照生成时机

以下操作**必须**生成新版本快照并递增 `meme_entries.current_version`：

```text
创建并发布首个正式版本（change_type = create）
修改主词条名称或标准化词形
修改义项释义或语境标签
新增、删除或停用义项
新增或删除重要变体
修改匹配规则
调整风险等级或策略开关（detect / display / generate / recommend）
发布、停用、归档词条
执行版本回滚
```

以下操作**可选**生成快照（由业务配置决定）：

```text
仅修改编辑备注、风险 notes
修正例句排版或非语义字段
新增证据记录但不改变词义判断
```

### 9.4.2 快照 JSON 结构

`snapshot` 字段为不可变 JSON 文档，记录该版本时刻词条的完整状态。建议结构如下：

```json
{
  "schema_version": "1.0",
  "meme_entry": { },
  "senses": [ ],
  "variants": [ ],
  "examples": [ ],
  "match_rules": [ ],
  "safety_policy": { },
  "evidence": [ ]
}
```

字段说明：

| 字段 | 说明 |
|---|---|
| `schema_version` | 快照格式版本，当前固定为 `"1.0"` |
| `meme_entry` | `meme_entries` 行快照，不含 `id` 以外的数据库自增依赖 |
| `senses` | 该版本下全部 `meme_senses` 行 |
| `variants` | 该版本下全部 `meme_variants` 行 |
| `examples` | 该版本下全部 `meme_examples` 行 |
| `match_rules` | 该版本下全部 `meme_match_rules` 行 |
| `safety_policy` | 该版本下 `meme_safety_policies` 行（单条或 null） |
| `evidence` | 该版本下全部 `meme_evidence` 行 |

快照中各子对象应保留原始 `id` 和 `sense_id` 等外键，以便差异比对。生成快照时从当前业务表读取，不依赖外部缓存。

### 9.4.3 回滚流程

回滚目标：将业务表恢复为指定历史版本 `meme_revisions.version` 的记录状态。

推荐步骤（须在事务中执行）：

```text
1. 校验目标版本存在且 snapshot 非空；
2. 对 meme_id 加行级锁（SELECT ... FOR UPDATE），并读取当前 current_version；
3. 读取目标 snapshot，并计算 `next_version = GREATEST(current_version, 该 meme 已有最大 revision.version) + 1`；
4. 删除该 meme_id 下全部子表记录（variants、examples、match_rules、evidence、senses）；
5. 删除或覆盖 meme_safety_policies；
6. 按 snapshot 顺序重建：senses → variants / examples / match_rules / evidence → safety_policy；
7. 用 snapshot.meme_entry 更新 meme_entries（保留 id、meme_code 和 current_version，更新其余字段）；
8. 将 meme_entries.current_version 设为 next_version；
9. 写入新 revision 记录：change_type = rollback，version = next_version，
   snapshot = 回滚后的当前全量状态，且 snapshot.meme_entry.current_version = next_version；change_summary 注明来源版本号。
```

说明：

- 回滚**不删除**历史 revision 记录，仅追加一条 `rollback` 类型记录；
- 回滚绝不将 `current_version` 写回目标历史版本；它始终递增为新的 `next_version`，保证审计链连续且不会与既有 revision 版本号冲突；
- 回滚后的 revision 的 `snapshot` 记录回滚完成后的实际状态，而非直接复制旧快照。

### 9.4.4 并发控制

同一 `meme_id` 的编辑、发布、回滚操作必须串行化：

```text
方案 A（推荐）：对 meme_entries 行加 SELECT ... FOR UPDATE 事务锁；
方案 B：使用分布式锁，键名 meme:edit:{meme_id}，超时后拒绝写入。
```

若检测到 `current_version` 与编辑开始时版本不一致，应拒绝提交并提示「词条已被他人修改，请刷新后重试」。

### 9.4.5 差异比对

版本差异比对基于两份 snapshot 的 JSON diff，建议比对维度：

```text
meme_entry 字段变更
senses 新增 / 删除 / 修改
variants 新增 / 删除
match_rules 新增 / 删除 / 启用状态变更
safety_policy 风险等级与策略开关变更
```

差异结果用于审核界面展示，不直接写回数据库。

---

# 10. 索引设计说明

| 表 | 索引 | 用途 |
|---|---|---|
| `meme_entries` | `uk_normalized_term_language` | 词条去重和标准化检索 |
| `meme_entries` | `idx_category_status` | 按分类和状态筛选 |
| `meme_entries` | `idx_trend_status` | 未来趋势模块按趋势变化状态查询 |
| `meme_senses` | `uk_meme_sense_no` | 保证同一词条内义项编号唯一 |
| `meme_variants` | `uk_meme_normalized_variant_type` | 保证同一词条、归一化写法和变体类型不重复 |
| `meme_variants` | `idx_normalized_variant` | 根据变体快速召回主词条 |
| `meme_match_rules` | `idx_rule_type_enabled` | 加载指定类型的启用规则 |
| `meme_examples` | `idx_example_meme_status` | 查询词条已审核例句 |
| `meme_evidence` | `idx_evidence_meme_role` | 查询某词条的特定证据类型 |
| `meme_revisions` | `uk_meme_version` | 保证一个词条的版本号唯一 |

---

# 11. 后续扩展方向

当 VibeLex 数据量、用户量或数据来源增长后，可考虑引入以下能力：

```text
1. data_sources / source_collection_tasks
   当接入 CHIME 之外的多个来源时，集中管理来源授权、停用状态、Connector 和采集计划。

2. meme_tags
   将 JSON 标签拆成标准化标签表，支持标签运营和统计。

3. meme_embeddings
   存储义项向量或关联外部向量数据库，实现语义召回。

4. meme_trend_metrics
   独立记录每日、每周、每月热度趋势。

5. meme_review_tasks
   当单级审核不能满足需要时，增加多人审核队列、任务分配、双人复核和质量抽检。

6. tenant_meme_entries
   支持企业客户私有词库、行业词包和多租户隔离。

7. OpenSearch / Elasticsearch
   支持大规模全文检索、分词检索和复杂筛选。
```

---

# 12. 版本记录

| 版本 | 日期 | 说明 |
|---|---|---|
| V1.0 | 2026-07-14 | 初始数据库设计，包含 8 张正式词条核心表 |
| V1.1 | 2026-07-15 | 增加 change set、CHIME 导入运行与候选表，统一操作者字段，补充义项风险覆盖和组合外键 |
| V1.2 | 2026-07-16 | 候选表支持人工录入、直接编辑、提交锁定、审核退回和直接发布；候选审核不再使用 change set 草稿 |
| V1.3 | 2026-07-16 | 趋势与生命周期枚举拆分；趋势默认改为 untracked，active/archived/obsolete 不再作为趋势状态 |
