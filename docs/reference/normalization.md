# VibeLex 词形归一化规范

**产品版本：** V1.0  
**文档修订：** 1.0  
**适用范围：** `meme_entries.normalized_term`、`meme_variants.normalized_variant`  
**关联字段：** `canonical_term`、`variant`、`language_code`

---

## 1. 文档目的

网络梗存在大量写法变体（大小写、空格、谐音、错别字、繁简等）。VibeLex 通过归一化词形实现：

- 词条去重（`uk_normalized_term_language`）；
- 变体去重（`uk_meme_normalized_variant_type`）；
- 识别引擎候选召回（`idx_normalized_variant`）；
- 跨来源数据对齐。

本文定义 V1 归一化规则。写入数据库前，**必须**对原始词形执行本规范；识别时对输入文本执行相同规则后再匹配。

---

## 2. 基本原则

```text
1. 确定性：相同输入 + 相同 language_code 必须产出相同 normalized 结果；
2. 可逆性非必需：归一化允许信息损失（如去除空格），展示仍用 canonical_term / variant；
3. 语言分治：不同 language_code 使用不同规则集，不混用；
4. 保留语义：归一化仅做形式统一，不做分词、同义词替换或语义推断；
5. 空值拒绝：归一化结果为空的词形不允许入库。
```

---

## 3. 通用预处理（所有语言）

对原始字符串依次执行：

### 3.1 Unicode 规范化

```text
NFKC 规范化（兼容分解后再组合）
```

作用：统一全角/半角、兼容字符（如 ﬁ → fi）、部分特殊符号。

### 3.2 空白处理

```text
1. 将 Tab、换行、不间断空格等统一为空格（U+0020）；
2. 合并连续空格为单个空格；
3. 去除首尾空格。
```

### 3.3 控制字符清除

```text
移除 ASCII 控制字符（U+0000–U+001F、U+007F）及零宽字符：
U+200B、U+200C、U+200D、U+FEFF
```

---

## 4. 简体中文（`zh-CN`）

在通用预处理后，依次执行：

### 4.1 繁体转简体

```text
使用 OpenCC（t2s）或等价库将繁体字转为简体。
```

示例：

```text
破防    → 破防（不变）
破防    → 破防（繁体「破防」与简体相同则不变）
```

### 4.2 中文标点统一

将全角中文标点保留；英文标点不在此步骤转换（由具体变体类型决定）。

### 4.3 英文字母大小写

```text
文本中的 ASCII 英文字母统一转为小写。
```

示例：

```text
YYDS    → yyds
treetree的 → treetree的
```

### 4.4 数字与中文数字

```text
V1 不做中文数字与阿拉伯数字互转（如「一」↔「1」）。
保留原文数字形式，仅做 NFKC 后的形式统一。
```

### 4.5 语气词与助词

```text
V1 不自动剥离「啊」「呀」「吧」等语气词。
若某变体需要去除后缀，应作为独立 variant 由编辑维护。
```

### 4.6 最终形式

```text
输出为 UTF-8 字符串，无首尾空格，内部无连续空格。
```

---

## 5. 繁体中文（`zh-TW`、`zh-HK`）

在通用预处理后：

```text
1. 不执行简繁转换（保留繁体）；
2. ASCII 英文字母转小写；
3. 其余规则同 §4.6。
```

---

## 6. 英文（`en`）

在通用预处理后：

```text
1. 全部 ASCII 字母转小写；
2. 连续空格合并（已在通用步骤处理）；
3. 撇号统一为 U+0027（'）；
4. V1 不做词干提取（stemming）或词形还原（lemmatization）。
```

---

## 7. 混合语言（`mixed`）

适用于中英混杂、数字谐音等表达（如「尊嘟假嘟」「xswl」）：

```text
1. 执行通用预处理；
2. 繁体转简体（仅作用于汉字部分，使用 OpenCC t2s）；
3. ASCII 字母转小写；
4. 不删除语言切换边界的空格（如 "tree tree的" 保留单空格）。
```

---

## 8. 拼音变体（`variant_type = pinyin`）

拼音类变体在 §4/§5/§6/§7 语言规则之后，额外执行：

```text
1. 去除声调符号（ā → a，ǖ → v 或 ü，V1 统一为 v）；
2. 去除音节间分隔符（空格、连字符、撇号）；
3. 全部小写。
```

示例：

```text
po fang   → pofang
pò fáng   → pofang
```

拼音归一化结果写入 `normalized_variant`；原始带声调写法保留在 `variant` 字段。

---

## 9. 空格变体（`variant_type = spacing_variant`）

```text
归一化时去除全部空格（在语言规则执行完毕后）。
```

示例：

```text
tree tree的 → treetree的
yy ds       → yyds
```

---

## 10. 大小写变体（`variant_type = case_variant`）

```text
归一化结果与 §4.3 / §6 一致（全小写）。
原始大小写写法保留在 variant 字段。
```

---

## 11. 写入约束

| 场景 | 规则 |
|---|---|
| `meme_entries` | `normalized_term = normalize(canonical_term, language_code)` |
| `meme_variants` | `normalized_variant = normalize(variant, language_code, variant_type)` |
| 去重 | 同一 `language_code` 下 `normalized_term` 全局唯一 |
| 变体去重 | 同一 `meme_id + normalized_variant + variant_type` 组合唯一；不同变体类型可保留相同归一化写法 |
| 空结果 | 归一化后长度为 0 则拒绝写入，返回校验错误 |

`variant_type` 为 `pinyin` 或 `spacing_variant` 时，在语言规则之后叠加 §8 或 §9 的额外步骤。

---

## 12. 识别时文本归一化

识别引擎不得在完成候选召回后再对原始 span 进行试探性归一化。引擎应在预处理阶段为原始输入构建可定位的归一化视图（normalized view），再在对应视图中完成召回和匹配。

V1 固定支持以下 profile：

| profile | 适用对象 | 规则 |
|---|---|---|
| `base` | 主词条、普通变体、`normalized_match` | 执行语言通用规则与语言规则，不移除词内空格 |
| `spacing` | `variant_type = spacing_variant` | 在 `base` 后移除全部空格 |
| `pinyin` | `variant_type = pinyin`、`pinyin_match` | 在 `base` 后去声调并移除音节分隔符 |

每个 profile 均须同时保存从归一化字符位置到原始文本 Unicode 码点半开区间 `[start_offset, end_offset)` 的映射。合并、删除或转换字符时，匹配 span 的原文范围取其首个和末个映射字符覆盖的最小原文区间；因此 `po fang` 在 `pinyin` profile 中命中 `pofang` 时，返回的原文 span 必须覆盖中间空格。

V1 不将汉字自动转为拼音；`pinyin` profile 仅用于输入本身含拼音写法的匹配。若后续加入汉字转拼音，必须作为新 profile 并提供独立评测集。

注意：

```text
1. `normalized_variant` 必须按其 variant_type 路由到对应 profile；不得以未知 variant_type 的单一归一化结果替代；
2. 上下文关键词规则（positive_context / negative_context）对关键词本身也执行 `base` profile 归一化；
3. 正则规则（regex_match）使用原始文本匹配，不先归一化（由 rule_config 控制）；
4. 输出 offset 一律使用原始输入的 Unicode 码点半开区间，不使用 UTF-8 字节偏移或 UTF-16 索引。
```

---

## 13. 跨词条冲突

不同主词条的 `normalized_variant` 允许相同（如多个梗共享写法）。冲突检测与消歧由识别引擎负责，不在归一化层解决。

归一化层仅保证：**同一词条、同一变体类型内，变体写法归一化后不重复**。

---

## 14. 实现建议

```text
1. 将归一化实现为独立纯函数模块（如 vibelex-normalize），供写入服务和识别引擎共用；
2. 为每种 language_code + variant_type 组合维护单元测试用例；
3. 归一化函数版本变更时，通过数据迁移任务批量更新 normalized_* 字段；
4. 在 snapshot 中记录归一化函数版本号（后续可在 schema_version 中扩展）。
```

---

## 15. 测试用例（zh-CN）

| 输入 | normalized 输出 |
|---|---|
| `YYDS` | `yyds` |
| `yy ds` | `yyds`（spacing_variant 类型） |
| `treetree的` | `treetree的` |
| `tree tree的` | `treetree的`（spacing_variant 类型） |
| `破防` | `破防` |
| `破 防` | `破防`（spacing_variant 类型） |
| `尊嘟假嘟` | `尊嘟假嘟` |
| `  yyds  ` | `yyds` |

---

## 16. 版本记录

| 版本 | 日期 | 说明 |
|---|---|---|
| V1.0 | 2026-07-14 | 初始归一化规范 |
