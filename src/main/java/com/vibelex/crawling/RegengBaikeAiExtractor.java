package com.vibelex.crawling;

import com.vibelex.crawling.CrawlConnector.CrawledEntry;
import com.vibelex.crawling.CrawlConnector.FetchedCrawlEntry;
import com.vibelex.crawling.CrawlConnector.OriginReference;
import com.vibelex.llm.LlmClientRegistry;
import com.vibelex.llm.LlmRequest;
import com.vibelex.llm.LlmScenarioProperties;
import com.vibelex.llm.PromptTemplateLoader;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class RegengBaikeAiExtractor implements CrawlEntryProcessor {
  private static final Logger log = LoggerFactory.getLogger(RegengBaikeAiExtractor.class);
  public static final String SCENARIO = "regengbaike-extraction";
  public static final String PROCESSOR_VERSION = "regengbaike-ai-extraction-v3";
  private static final Set<String> FIELDS =
      Set.of(
          "is_valid_meme",
          "definition",
          "origin",
          "examples",
          "category",
          "confidence",
          "needs_review",
          "issues");
  private static final Set<String> CATEGORIES =
      Set.of(
          "homophone",
          "abbreviation",
          "number_code",
          "template_phrase",
          "slang",
          "emotion_expression",
          "sarcasm",
          "foreign_term",
          "fandom_term",
          "game_term",
          "acg_term",
          "livestream_term",
          "workplace_term",
          "other");
  private final LlmScenarioProperties properties;
  private final LlmClientRegistry clients;
  private final PromptTemplateLoader prompts;
  private final ObjectMapper mapper;

  public RegengBaikeAiExtractor(
      LlmScenarioProperties properties,
      LlmClientRegistry clients,
      PromptTemplateLoader prompts,
      ObjectMapper mapper) {
    this.properties = properties;
    this.clients = clients;
    this.prompts = prompts;
    this.mapper = mapper;
  }

  @Override
  public boolean supports(String sourceCode) {
    return RegengBaikeConnector.SOURCE_CODE.equals(sourceCode);
  }

  @Override
  public String processorVersion() {
    return PROCESSOR_VERSION;
  }

  @Override
  public ProcessedEntry restore(FetchedCrawlEntry source, String aiOutput, String aiModel) {
    return processOutput(source, aiOutput, providerName(), aiModel);
  }

  public void validateConfiguration() {
    LlmScenarioProperties.Scenario scenario = properties.scenario(SCENARIO);
    if (!scenario.isEnabled()) throw new IllegalStateException("热梗百科 AI 提取场景未启用");
    require(scenario.getProvider(), "scenario.provider");
    require(scenario.getPrompt(), "scenario.prompt");
    LlmScenarioProperties.Provider provider = properties.provider(scenario.getProvider());
    require(provider.getProtocol(), "provider.protocol");
    require(provider.getBaseUrl(), "provider.base-url");
    require(provider.getApiKey(), "provider.api-key");
    require(provider.getModel(), "provider.model");
    clients.client(provider.getProtocol());
    prompts.load(scenario.getPrompt());
  }

  String providerName() {
    return properties.scenario(SCENARIO).getProvider();
  }

  @Override
  public ProcessedEntry process(FetchedCrawlEntry source) {
    validateConfiguration();
    LlmScenarioProperties.Scenario scenario = properties.scenario(SCENARIO);
    LlmScenarioProperties.Provider provider = properties.provider(scenario.getProvider());
    String input = sourceInput(source, scenario.getMaximumSourceCharacters());
    long startedAt = System.nanoTime();
    try {
      String output =
          clients
              .client(provider.getProtocol())
              .complete(
                  new LlmRequest(
                      provider.getModel(),
                      prompts.load(scenario.getPrompt()),
                      input,
                      scenario.getTemperature(),
                      provider.getRequestTimeoutSeconds()));
      ProcessedEntry result =
          processOutput(source, output, scenario.getProvider(), provider.getModel());
      log.info(
          "热梗百科 AI 提取完成 source_record_key={} provider={} model={} processor_version={} elapsed_ms={} result={}",
          source.sourceRecordKey(),
          scenario.getProvider(),
          provider.getModel(),
          PROCESSOR_VERSION,
          java.time.Duration.ofNanos(System.nanoTime() - startedAt).toMillis(),
          result.ignoredReason() == null ? "succeeded" : "ignored");
      return result;
    } catch (RuntimeException e) {
      log.warn(
          "热梗百科 AI 提取失败 source_record_key={} provider={} model={} processor_version={} elapsed_ms={} result=failed",
          source.sourceRecordKey(),
          scenario.getProvider(),
          provider.getModel(),
          PROCESSOR_VERSION,
          java.time.Duration.ofNanos(System.nanoTime() - startedAt).toMillis());
      throw e;
    }
  }

  ProcessedEntry processOutput(
      FetchedCrawlEntry source, String output, String providerName, String model) {
    Extraction extraction = parse(output, source);
    if (!extraction.validMeme())
      return ProcessedEntry.ignored(PROCESSOR_VERSION, model, output, "AI 判断不是网络梗");
    CrawledEntry entry =
        new CrawledEntry(
            source.term(),
            extraction.definition(),
            extraction.examples(),
            extraction.category(),
            source.sourceCategory(),
            source.sourceTags(),
            source.sourceUrl(),
            source.sourceRecordKey(),
            source.parserVersion(),
            extraction.origin(),
            originReferences(source, extraction.origin()),
            extraction.needsReview()
                || extraction
                        .confidence()
                        .compareTo(properties.scenario(SCENARIO).getMinimumConfidence())
                    < 0,
            extraction.issues(),
            providerName,
            model,
            PROCESSOR_VERSION,
            extraction.confidence());
    return ProcessedEntry.imported(entry, PROCESSOR_VERSION, model, output);
  }

  private List<OriginReference> originReferences(FetchedCrawlEntry source, String origin) {
    if (origin == null
        || origin.isBlank()
        || source.sourceUrl() == null
        || source.sourceUrl().isBlank()) return List.of();
    return List.of(new OriginReference("热梗百科：" + source.term(), source.sourceUrl()));
  }

  Extraction parse(String output, FetchedCrawlEntry source) {
    try {
      String json = output.trim();
      if (json.startsWith("```"))
        json = json.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
      JsonNode root = mapper.readTree(json);
      if (!root.isObject()) throw new IllegalArgumentException("AI 输出根节点必须是对象");
      for (var property : root.properties())
        if (!FIELDS.contains(property.getKey()))
          throw new IllegalArgumentException("AI 输出包含未知字段: " + property.getKey());
      requireType(root, "is_valid_meme", "boolean");
      requireType(root, "examples", "array");
      requireType(root, "category", "string");
      requireType(root, "confidence", "number");
      requireType(root, "needs_review", "boolean");
      requireType(root, "issues", "array");
      boolean valid = root.path("is_valid_meme").asBoolean();
      String definition = root.path("definition").asText("").trim();
      if (valid && definition.isBlank()) throw new IllegalArgumentException("AI 释义不能为空");
      if (definition.length() > 500) throw new IllegalArgumentException("AI 释义超过 500 字符");
      String origin = root.path("origin").asText("").trim();
      if (origin.length() > 2000) throw new IllegalArgumentException("AI 来源背景超过 2000 字符");
      String category = root.path("category").asText();
      if (!CATEGORIES.contains(category))
        throw new IllegalArgumentException("AI 返回非法分类: " + category);
      var confidence = root.path("confidence").decimalValue();
      if (confidence.signum() < 0 || confidence.compareTo(java.math.BigDecimal.ONE) > 0)
        throw new IllegalArgumentException("AI confidence 必须在 0 到 1 之间");
      List<String> issues = strings(root.path("issues"), 50, 200);
      List<String> examples = strings(root.path("examples"), 3, 2000);
      if (valid && examples.size() != 3) throw new IllegalArgumentException("有效网络梗必须包含 3 条互不重复的例句");
      if (!valid && !examples.isEmpty()) throw new IllegalArgumentException("无效网络梗的 examples 必须为空");
      boolean needsReview = root.path("needs_review").asBoolean();
      return new Extraction(
          valid,
          definition,
          origin,
          List.copyOf(examples),
          category,
          confidence,
          needsReview,
          List.copyOf(issues));
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalArgumentException("无法解析 AI 提取结果", e);
    }
  }

  private String sourceInput(FetchedCrawlEntry source, int maximum) {
    var node = mapper.createObjectNode();
    node.put("term", source.term());
    node.put("source_summary", source.sourceSummary());
    String body = source.sourceBody() == null ? "" : source.sourceBody();
    int limit =
        Math.max(
            1000,
            maximum
                - source.term().length()
                - (source.sourceSummary() == null ? 0 : source.sourceSummary().length())
                - 500);
    boolean truncated = body.length() > limit;
    node.put("source_body", truncated ? body.substring(0, limit) : body);
    node.put("source_category", source.sourceCategory());
    if (source.sourcePublishedAt() != null)
      node.put("source_published_at", source.sourcePublishedAt().toString());
    node.put("source_truncated", truncated);
    try {
      return mapper.writeValueAsString(node);
    } catch (Exception e) {
      throw new IllegalStateException("无法构造 AI 输入", e);
    }
  }

  private List<String> strings(JsonNode values, int maximumItems, int maximumLength) {
    if (!values.isArray()) throw new IllegalArgumentException("AI 数组字段类型错误");
    LinkedHashSet<String> result = new LinkedHashSet<>();
    for (JsonNode value : values) {
      if (!value.isTextual()) throw new IllegalArgumentException("AI 数组元素必须是字符串");
      String text = value.asText().trim();
      if (text.length() > maximumLength) throw new IllegalArgumentException("AI 数组元素过长");
      if (!text.isBlank()) result.add(text);
      if (result.size() >= maximumItems) break;
    }
    return new ArrayList<>(result);
  }

  private void requireType(JsonNode root, String field, String type) {
    JsonNode value = root.get(field);
    if (value == null) throw new IllegalArgumentException("AI 输出缺少字段: " + field);
    boolean valid =
        switch (type) {
          case "boolean" -> value.isBoolean();
          case "array" -> value.isArray();
          case "string" -> value.isTextual();
          case "number" -> value.isNumber();
          default -> false;
        };
    if (!valid) throw new IllegalArgumentException("AI 字段类型错误: " + field);
  }

  private String require(String value, String field) {
    if (value == null || value.isBlank()) throw new IllegalStateException("LLM 配置缺失: " + field);
    return value;
  }

  record Extraction(
      boolean validMeme,
      String definition,
      String origin,
      List<String> examples,
      String category,
      java.math.BigDecimal confidence,
      boolean needsReview,
      List<String> issues) {}
}
