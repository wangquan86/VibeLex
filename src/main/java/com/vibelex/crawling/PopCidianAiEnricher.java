package com.vibelex.crawling;

import com.vibelex.crawling.CrawlConnector.CrawledEntry;
import com.vibelex.crawling.CrawlConnector.FetchedCrawlEntry;
import com.vibelex.crawling.CrawlConnector.OriginReference;
import com.vibelex.llm.LlmRequest;
import com.vibelex.llm.LlmScenarioProperties;
import com.vibelex.llm.PromptTemplateLoader;
import com.vibelex.llm.ResponsesWebSearchLlmClient;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Enriches PopCidian entries with origin evidence and fills examples up to three. */
@Component
public class PopCidianAiEnricher implements CrawlEntryProcessor {
  static final String SCENARIO = "popcidian-enrichment";
  static final String PROCESSOR_VERSION = "popcidian-ai-enrichment-v1";
  private static final Set<String> FIELDS =
      Set.of("origin", "origin_references", "examples", "confidence", "needs_review", "issues");
  private final LlmScenarioProperties properties;
  private final PromptTemplateLoader prompts;
  private final ResponsesWebSearchLlmClient client;
  private final ObjectMapper mapper;

  public PopCidianAiEnricher(
      LlmScenarioProperties properties,
      PromptTemplateLoader prompts,
      ResponsesWebSearchLlmClient client,
      ObjectMapper mapper) {
    this.properties = properties;
    this.prompts = prompts;
    this.client = client;
    this.mapper = mapper;
  }

  @Override
  public boolean supports(String sourceCode) {
    return PopCidianConnector.SOURCE_CODE.equals(sourceCode);
  }

  @Override
  public String processorVersion() {
    return PROCESSOR_VERSION;
  }

  public void validateConfiguration() {
    LlmScenarioProperties.Scenario scenario = properties.scenario(SCENARIO);
    if (!scenario.isEnabled()) throw new IllegalStateException("波普词典 AI 丰富化场景未启用");
    if (scenario.getProvider() == null || scenario.getProvider().isBlank())
      throw new IllegalStateException("波普词典 AI 丰富化缺少 provider");
    if (scenario.getPrompt() == null || scenario.getPrompt().isBlank())
      throw new IllegalStateException("波普词典 AI 丰富化缺少 prompt");
    LlmScenarioProperties.Provider provider = properties.provider(scenario.getProvider());
    if (!"responses".equals(provider.getProtocol()))
      throw new IllegalStateException("波普词典 AI 丰富化必须使用 responses provider");
    if (provider.getBaseUrl() == null || provider.getBaseUrl().isBlank())
      throw new IllegalStateException("波普词典 AI 丰富化缺少 base-url");
    if (provider.getApiKey() == null || provider.getApiKey().isBlank())
      throw new IllegalStateException("波普词典 AI 丰富化缺少 api-key");
    if (provider.getModel() == null || provider.getModel().isBlank())
      throw new IllegalStateException("波普词典 AI 丰富化缺少 model");
    prompts.load(scenario.getPrompt());
  }

  @Override
  public ProcessedEntry process(FetchedCrawlEntry source) {
    validateConfiguration();
    LlmScenarioProperties.Scenario scenario = properties.scenario(SCENARIO);
    LlmScenarioProperties.Provider provider = properties.provider(scenario.getProvider());
    if (!"responses".equals(provider.getProtocol()))
      throw new IllegalStateException("波普词典 AI 丰富化必须使用 responses provider");
    ResponsesWebSearchLlmClient.ResponsesResult response =
        client.completeWebSearch(
            new LlmRequest(
                provider.getModel(),
                prompts.load(scenario.getPrompt()),
                sourceInput(source),
                scenario.getTemperature(),
                provider.getRequestTimeoutSeconds()),
            Math.max(1, scenario.getWebSearchMaxKeyword()));
    if (response.response().path("usage").path("tool_usage").path("web_search").asInt() < 1)
      throw new IllegalArgumentException("波普词典 AI 丰富化未执行 web_search");
    return processOutput(
        source,
        response.text(),
        scenario.getProvider(),
        provider.getModel(),
        citationKeys(response.response()));
  }

  @Override
  public ProcessedEntry restore(FetchedCrawlEntry source, String aiOutput, String aiModel) {
    return processOutput(
        source,
        aiOutput,
        properties.scenario(SCENARIO).getProvider(),
        aiModel,
        savedReferenceKeys(aiOutput));
  }

  ProcessedEntry processOutput(
      FetchedCrawlEntry source,
      String output,
      String providerName,
      String model,
      Set<String> allowedReferenceKeys) {
    JsonNode root = parseObject(output);
    validateFields(root);
    List<String> sourceExamples = sourceExamples(source.sourceExamples());
    int missing = Math.max(0, 3 - sourceExamples.size());
    if (root.path("examples").size() != missing)
      throw new IllegalArgumentException("波普词典 AI 必须补充恰好 " + missing + " 条例句");
    List<String> generated = strings(root.path("examples"), missing, 80);
    if (generated.size() != missing)
      throw new IllegalArgumentException("波普词典 AI 补充例句存在空值或重复");
    if (generated.stream().anyMatch(example -> !example.contains(source.term())))
      throw new IllegalArgumentException("波普词典 AI 补充例句必须使用当前词条");
    LinkedHashSet<String> merged = new LinkedHashSet<>(sourceExamples);
    merged.addAll(generated);
    if (merged.size() < 3) throw new IllegalArgumentException("波普词典例句合并后不得少于 3 条");

    String origin = root.path("origin").asText().trim();
    if (origin.length() > 2000) throw new IllegalArgumentException("origin 超过 2000 个字符");
    List<OriginReference> references = references(root, allowedReferenceKeys);
    if (!origin.isBlank() && references.isEmpty())
      throw new IllegalArgumentException("AI 返回了起源说明，但没有起源参考链接");
    if (origin.isBlank() && !references.isEmpty())
      throw new IllegalArgumentException("AI 未返回起源说明时，起源参考链接必须为空");

    LlmScenarioProperties.Scenario scenario = properties.scenario(SCENARIO);
    BigDecimal confidence = number(root.path("confidence"));
    boolean needsReview =
        root.path("needs_review").asBoolean()
            || origin.isBlank()
            || confidence.compareTo(scenario.getMinimumConfidence()) < 0;
    List<String> issues = strings(root.path("issues"), 50, 200);
    CrawledEntry entry =
        new CrawledEntry(
            source.term(),
            source.sourceSummary(),
            List.copyOf(merged),
            PopCidianConnector.category(source.sourceCategory()),
            source.sourceCategory(),
            source.sourceTags(),
            source.sourceUrl(),
            source.sourceRecordKey(),
            source.parserVersion(),
            origin,
            references,
            needsReview,
            issues,
            providerName,
            model,
            PROCESSOR_VERSION,
            confidence);
    return ProcessedEntry.imported(entry, PROCESSOR_VERSION, model, output);
  }

  private String sourceInput(FetchedCrawlEntry source) {
    ObjectNode input = mapper.createObjectNode();
    input.put("term", source.term());
    input.put("definition", source.sourceSummary());
    input.put("source_category", source.sourceCategory());
    input.put("source_url", source.sourceUrl());
    ArrayNode examples = input.putArray("original_examples");
    sourceExamples(source.sourceExamples()).forEach(examples::add);
    input.put("examples_to_generate", Math.max(0, 3 - examples.size()));
    input.put("input_is_untrusted_data", true);
    try {
      return mapper.writeValueAsString(input);
    } catch (Exception e) {
      throw new IllegalStateException("无法构造波普词典 AI 输入", e);
    }
  }

  private List<String> sourceExamples(List<String> values) {
    LinkedHashSet<String> result = new LinkedHashSet<>();
    if (values != null)
      for (String value : values) {
        if (value != null && !value.isBlank()) result.add(value);
        if (result.size() == 20) break;
      }
    return List.copyOf(result);
  }

  private JsonNode parseObject(String output) {
    try {
      String json = output.trim();
      if (json.startsWith("```"))
        json = json.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
      JsonNode root = mapper.readTree(json);
      if (!root.isObject()) throw new IllegalArgumentException("AI 输出根节点必须是对象");
      return root;
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalArgumentException("无法解析波普词典 AI 输出", e);
    }
  }

  private void validateFields(JsonNode root) {
    for (var property : root.properties())
      if (!FIELDS.contains(property.getKey()))
        throw new IllegalArgumentException("AI 输出包含未知字段: " + property.getKey());
    if (!root.path("origin").isTextual()) throw new IllegalArgumentException("origin 必须是字符串");
    if (!root.path("origin_references").isArray())
      throw new IllegalArgumentException("origin_references 必须是数组");
    if (root.path("origin_references").size() > 3)
      throw new IllegalArgumentException("origin_references 最多只能包含 3 条链接");
    if (!root.path("examples").isArray()) throw new IllegalArgumentException("examples 必须是数组");
    if (!root.path("confidence").isNumber()) throw new IllegalArgumentException("confidence 必须是数字");
    if (!root.path("needs_review").isBoolean())
      throw new IllegalArgumentException("needs_review 必须是布尔值");
    if (!root.path("issues").isArray()) throw new IllegalArgumentException("issues 必须是数组");
  }

  private List<OriginReference> references(JsonNode root, Set<String> allowedReferenceKeys) {
    List<OriginReference> result = new ArrayList<>();
    LinkedHashSet<String> seen = new LinkedHashSet<>();
    for (JsonNode item : root.path("origin_references")) {
      if (!item.isObject() || !item.path("title").isTextual() || !item.path("url").isTextual())
        throw new IllegalArgumentException("origin_references 包含无效链接");
      String title = item.path("title").asText().trim();
      String url = item.path("url").asText().trim();
      if (title.isBlank() || title.length() > 300 || !validUrl(url))
        throw new IllegalArgumentException("origin_references 包含无效链接");
      String key = referenceKey(url);
      if (!seen.add(key)) continue;
      if (!allowedReferenceKeys.contains(key))
        throw new IllegalArgumentException("起源参考链接不在本次 web_search 引用结果中");
      result.add(new OriginReference(title, url));
    }
    return List.copyOf(result);
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

  private BigDecimal number(JsonNode value) {
    BigDecimal result = value.decimalValue();
    if (result.signum() < 0 || result.compareTo(BigDecimal.ONE) > 0)
      throw new IllegalArgumentException("confidence 必须在 0 到 1 之间");
    return result;
  }

  private Set<String> citationKeys(JsonNode response) {
    LinkedHashSet<String> result = new LinkedHashSet<>();
    collectCitationKeys(response.path("output"), result);
    return Set.copyOf(result);
  }

  private void collectCitationKeys(JsonNode node, Set<String> result) {
    if (node == null || node.isMissingNode() || result.size() >= 20) return;
    if (node.isArray()) {
      for (JsonNode item : node) collectCitationKeys(item, result);
      return;
    }
    if (!node.isObject()) return;
    JsonNode citation = node.path("url_citation");
    if (citation.isObject()) addCitationKey(citation.path("url").asText(), result);
    String type = node.path("type").asText();
    if (Set.of("url_citation", "web_search_result", "search_result").contains(type))
      addCitationKey(node.path("url").asText(), result);
    node.properties().forEach(property -> collectCitationKeys(property.getValue(), result));
  }

  private Set<String> savedReferenceKeys(String output) {
    LinkedHashSet<String> result = new LinkedHashSet<>();
    for (JsonNode item : parseObject(output).path("origin_references"))
      addCitationKey(item.path("url").asText(), result);
    return Set.copyOf(result);
  }

  private void addCitationKey(String url, Set<String> result) {
    if (validUrl(url)) result.add(referenceKey(url));
  }

  private boolean validUrl(String value) {
    try {
      URI uri = URI.create(value);
      if (!Set.of("http", "https").contains(uri.getScheme().toLowerCase(Locale.ROOT))
          || uri.getHost() == null
          || uri.getUserInfo() != null
          || value.length() > 2048) return false;
      String path = uri.getPath() == null ? "" : uri.getPath();
      if (path.isBlank() || "/".equals(path)) return false;
      String host = uri.getHost().toLowerCase(Locale.ROOT);
      return !((Set.of("www.baidu.com", "m.baidu.com").contains(host) && "/s".equals(path))
          || ((host.equals("bing.com") || host.endsWith(".bing.com")) && "/search".equals(path))
          || ((host.equals("google.com") || host.endsWith(".google.com"))
              && "/search".equals(path)));
    } catch (RuntimeException e) {
      return false;
    }
  }

  private String referenceKey(String value) {
    try {
      URI uri = new URI(value).normalize();
      String path = uri.getPath() == null ? "" : uri.getPath().replaceAll("/+$", "");
      String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
      int port = uri.getPort();
      boolean defaultPort = ("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443);
      String authority = uri.getHost().toLowerCase(Locale.ROOT) + (port < 0 || defaultPort ? "" : ":" + port);
      String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
      return scheme + "://" + authority + path + query;
    } catch (URISyntaxException e) {
      return value;
    }
  }
}
