# V1 AI 变体生成

## 1. 范围与边界

V1 支持在候选词条编辑页手动生成 AI 词形变体，也可在候选审核批准、正式发布前自动生成。候选页面允许人工保留、删除或补充变体；最终发布时以候选词条当前保存的变体列表为准。

支持的变体类型为 `alias`、`abbreviation`、`pinyin`、`homophone`、`typo_variant`，单次最多保留 3 条。服务端会执行归一化、自身重复、候选内重复和已发布词条冲突检查；AI 结果不会覆盖人工录入的变体。

AI 只提供建议，不构成自动发布依据。候选词条仍须经人工审核后发布。

## 2. 触发方式

| 方式 | 触发位置 | 是否受 `enabled` 控制 |
|---|---|---|
| 手动生成 | 候选编辑页、正式词条详情页 | 否；只要 AI 场景配置完整即可调用 |
| 自动生成 | 候选审核批准、正式发布前 | 是 |

`variant-generation.enabled` 仅控制自动生成。模型调用、联网搜索或输出校验失败时，不回滚已批准的主词条；主词条照常发布，失败会记录服务端警告日志。

已发布词条详情页可重新生成 AI 变体。该操作仅替换 `source_method = ai_suggested` 的现有变体，保留人工维护的变体，并发布新版本、刷新识别索引。

正式词条撤回到候选池后，候选词条是再次发布时的变体事实来源：重新发布会替换旧变体，而不会从旧快照恢复已经删除的变体。

## 3. Responses API 与联网搜索

所有 AI 变体调用使用 Responses API：`POST {base-url}/responses`，不保留 Chat Completions 模式。请求将提示词放入 `instructions`，将本次任务放入 `input`，并启用内置 `web_search` 工具。

```yaml
vibelex:
  llm:
    scenarios:
      variant-generation:
        enabled: false
        base-url: ${VIBELEX_VARIANT_LLM_BASE_URL}
        api-key: ${VIBELEX_VARIANT_LLM_API_KEY}
        model: ${VIBELEX_VARIANT_LLM_MODEL}
        prompt: classpath:prompts/variant-generation.md
        temperature: 0.2
        web-search-max-keyword: 2
        request-timeout-seconds: 90
```

`web-search-max-keyword` 范围为 1–50。服务端要求本次响应实际触发至少一次联网搜索，并且能提取到有效的结构化 URL 引用；否则直接丢弃该次 AI 结果。

日志级别为 `DEBUG` 时，`AiVariantGenerator` 会输出完整、未截断的 Responses 原始响应，用于排查模型、搜索和引用问题。

## 4. 提示词与输出契约

提示词位于 `src/main/resources/prompts/variant-generation.md`，必须包含 `{{term}}` 和 `{{definition}}`。模板强调：

- 以本次给定的网络语义判断等价性；
- 不把普通词义、方言义、古义、词典近义或读音说明迁移为网络变体；
- 禁止把拼音、注音、读音、音标字段当作变体；禁止带声调、空格分词的教学拼音；
- 谐音、别名、拼音和错别字必须有真实公开网络使用证据；不确定时输出空数组。

模型只返回 JSON 对象：

```json
{
  "variants": [
    {"variant": "示例", "variant_type": "alias", "confidence": 0.95}
  ]
}
```

模型不得输出 URL。服务端仅从 Responses API 的结构化 `url_citation` / 搜索结果节点提取链接，并拒绝主页、搜索页等无效 URL。

## 5. AI变体参考来源

一次 Responses 请求可能生成多个变体，同时返回一组联网引用。当前方舟响应不会可靠地指出“哪条引用证明哪条变体”，因此这些链接是**词条级的 AI变体参考来源**，不是某个单独变体的专属证据。

服务端最多保留该次响应中前两条有效结构化引用，并按 URL 去重。候选编辑页、候选详情页和正式词条详情页均以“AI变体参考来源”公共区域展示，不在单条变体旁显示来源链接。

逐条变体核验及变体—证据的一一关联不属于当前 V1 范围。
