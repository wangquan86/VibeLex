package com.vibelex.sourceingestion.application;

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
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Web-search enrichment for file imports; CHIME specializes it without example generation. */
@Component
public class BuzzwordAiEnricher implements ImportRecordEnricher {
  private static final int MAX_ORIGIN_REFERENCES = 3;
  private static final Set<String> ORIGIN_FIELDS =
      Set.of("origin", "origin_references", "confidence", "needs_review", "issues");
  private final LlmScenarioProperties properties;
  private final PromptTemplateLoader prompts;
  private final ResponsesWebSearchLlmClient client;
  private final ObjectMapper mapper;
  private final String sourceCode;
  private final String scenarioName;
  private final String processorVersion;
  private final boolean generateExamples;

  @Autowired
  public BuzzwordAiEnricher(
      LlmScenarioProperties properties,
      PromptTemplateLoader prompts,
      ResponsesWebSearchLlmClient client,
      ObjectMapper mapper) {
    this(
        properties,
        prompts,
        client,
        mapper,
        "buzzword",
        "buzzword-enrichment",
        "buzzword-ai-enrichment-v2",
        true);
  }

  protected BuzzwordAiEnricher(
      LlmScenarioProperties properties,
      PromptTemplateLoader prompts,
      ResponsesWebSearchLlmClient client,
      ObjectMapper mapper,
      String sourceCode,
      String scenarioName,
      String processorVersion,
      boolean generateExamples) {
    this.properties = properties;
    this.prompts = prompts;
    this.client = client;
    this.mapper = mapper;
    this.sourceCode = sourceCode;
    this.scenarioName = scenarioName;
    this.processorVersion = processorVersion;
    this.generateExamples = generateExamples;
  }

  @Override
  public boolean supports(String sourceCode) {
    return this.sourceCode.equals(sourceCode);
  }

  @Override
  public EnrichedRecord enrich(String term, String definition, Map<String, Object> processingNote) {
    LlmScenarioProperties.Scenario scenario = properties.scenario(scenarioName);
    if (!scenario.isEnabled()) throw new IllegalStateException(sourceCode + " AI 丰富化场景未启用");
    LlmScenarioProperties.Provider provider = properties.provider(scenario.getProvider());
    if (!"responses".equals(provider.getProtocol()))
      throw new IllegalStateException(sourceCode + " AI 丰富化必须使用 responses provider");
    String prompt = prompts.load(scenario.getPrompt());
    String input =
        sourceInput(term, definition, processingNote, scenario.getMaximumSourceCharacters());
    ResponsesWebSearchLlmClient.ResponsesResult response =
        client.completeWebSearch(
            new LlmRequest(
                provider.getModel(),
                prompt,
                input,
                scenario.getTemperature(),
                provider.getRequestTimeoutSeconds()),
            Math.max(1, scenario.getWebSearchMaxKeyword()));
    if (response.response().path("usage").path("tool_usage").path("web_search").asInt() < 1)
      throw new IllegalArgumentException(sourceCode + " AI 丰富化未执行 web_search");
    Set<String> citationKeys = citationKeys(response.response());
    return buildResult(
        term,
        definition,
        processingNote,
        response.text(),
        scenario.getProvider(),
        provider.getModel(),
        citationKeys);
  }

  @Override
  public EnrichedRecord restore(
      String term,
      String definition,
      Map<String, Object> processingNote,
      String aiOutput,
      String provider,
      String model) {
    return buildResult(
        term,
        definition,
        processingNote,
        aiOutput,
        provider,
        model,
        savedReferenceKeys(processingNote));
  }

  private EnrichedRecord buildResult(
      String term,
      String definition,
      Map<String, Object> processingNote,
      String output,
      String providerName,
      String model,
      Set<String> allowedReferenceKeys) {
    LlmScenarioProperties.Scenario scenario = properties.scenario(scenarioName);
    JsonNode root = parseObject(output);
    validateFields(root);
    String origin = root.path("origin").asText("").trim();
    if (origin.length() > 2000) throw new IllegalArgumentException("origin 超过 2000 个字符");
    List<String> generatedExamples = List.of();
    if (generateExamples) {
      if (root.path("examples").size() != 3)
        throw new IllegalArgumentException("Buzzword AI 必须返回恰好 3 条例句");
      generatedExamples = strings(root.path("examples"), 3, 80);
      if (generatedExamples.size() != 3)
        throw new IllegalArgumentException("Buzzword AI 必须返回 3 条不重复例句");
      if (term == null
          || term.isBlank()
          || generatedExamples.stream().anyMatch(example -> !example.contains(term)))
        throw new IllegalArgumentException("每条 Buzzword AI 例句都必须使用当前词条");
    }
    BigDecimal confidence = number(root, "confidence");
    boolean needsReview = root.path("needs_review").asBoolean(false);
    if (confidence.compareTo(scenario.getMinimumConfidence()) < 0) needsReview = true;
    List<String> issues = strings(root.path("issues"), 50, 200);
    ObjectNode note = mapper.createObjectNode();
    if (processingNote != null) note = mapper.valueToTree(processingNote);
    note.put("origin", origin);
    ArrayNode refs = note.putArray("origin_references");
    LinkedHashSet<String> seenReferenceKeys = new LinkedHashSet<>();
    if (root.path("origin_references").isArray()) {
      for (JsonNode ref : root.path("origin_references")) {
        String url = ref.path("url").asText("").trim();
        String key = referenceKey(url);
        if (!validUrl(url) || !seenReferenceKeys.add(key)) continue;
        if (!allowedReferenceKeys.contains(key))
          throw new IllegalArgumentException("起源参考链接不在本次 web_search 引用结果中");
        ObjectNode saved = refs.addObject();
        String title = ref.path("title").asText("").trim();
        if (title.length() > 300) throw new IllegalArgumentException("起源参考标题超过 300 个字符");
        saved.put("title", title);
        saved.put("url", url);
      }
    }
    if (!origin.isBlank() && refs.isEmpty())
      throw new IllegalArgumentException("AI 返回了起源说明，但没有起源参考链接");
    if (origin.isBlank() && !refs.isEmpty())
      throw new IllegalArgumentException("AI 未返回起源说明时，起源参考链接必须为空");
    if (origin.isBlank()) needsReview = true;
    if (generateExamples) note.putArray("examples").addAll(textArray(generatedExamples));
    ObjectNode ai = note.putObject("ai_enrichment");
    ai.put("provider", providerName);
    ai.put("model", model);
    ai.put("processor_version", processorVersion);
    ai.put("confidence", confidence);
    ai.put("needs_review", needsReview);
    ArrayNode issueArray = ai.putArray("issues");
    issues.forEach(issueArray::add);
    return new EnrichedRecord(
        definition,
        mapper.convertValue(note, Map.class),
        providerName,
        model,
        processorVersion,
        output,
        null);
  }

  private JsonNode parseObject(String text) {
    try {
      String json = text.trim();
      if (json.startsWith("```"))
        json = json.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
      JsonNode root = mapper.readTree(json);
      if (!root.isObject()) throw new IllegalArgumentException("AI 输出根节点必须是对象");
      return root;
    } catch (Exception e) {
      throw new IllegalArgumentException("无法解析 " + sourceCode + " AI 输出", e);
    }
  }

  private void validateFields(JsonNode root) {
    for (var property : root.properties())
      if (!outputFields().contains(property.getKey()))
        throw new IllegalArgumentException("AI 输出包含未知字段: " + property.getKey());
    if (!root.path("origin").isTextual()) throw new IllegalArgumentException("origin 必须是字符串");
    if (!root.path("origin_references").isArray())
      throw new IllegalArgumentException("origin_references 必须是数组");
    if (root.path("origin_references").size() > MAX_ORIGIN_REFERENCES)
      throw new IllegalArgumentException("origin_references 最多只能包含 3 条链接");
    if (generateExamples && !root.path("examples").isArray())
      throw new IllegalArgumentException("examples 必须是数组");
    if (!root.path("confidence").isNumber()) throw new IllegalArgumentException("confidence 必须是数字");
    if (!root.path("needs_review").isBoolean())
      throw new IllegalArgumentException("needs_review 必须是布尔值");
    if (!root.path("issues").isArray()) throw new IllegalArgumentException("issues 必须是数组");
    for (JsonNode ref : root.path("origin_references")) {
      if (!ref.isObject()
          || !ref.path("title").isTextual()
          || ref.path("title").asText().isBlank()
          || !ref.path("url").isTextual()
          || !validUrl(ref.path("url").asText()))
        throw new IllegalArgumentException("origin_references 包含无效链接");
    }
  }

  private Set<String> outputFields() {
    if (!generateExamples) return ORIGIN_FIELDS;
    LinkedHashSet<String> fields = new LinkedHashSet<>(ORIGIN_FIELDS);
    fields.add("examples");
    return fields;
  }

  private BigDecimal number(JsonNode root, String field) {
    BigDecimal value = root.path(field).decimalValue();
    if (value.signum() < 0 || value.compareTo(BigDecimal.ONE) > 0)
      throw new IllegalArgumentException(field + " 必须在 0 到 1 之间");
    return value;
  }

  private List<String> strings(JsonNode values, int maxItems, int maxLength) {
    if (!values.isArray()) throw new IllegalArgumentException("AI 数组字段类型错误");
    LinkedHashSet<String> result = new LinkedHashSet<>();
    for (JsonNode value : values) {
      if (!value.isTextual()) throw new IllegalArgumentException("AI 数组元素必须是字符串");
      String text = value.asText().trim();
      if (text.length() > maxLength) throw new IllegalArgumentException("AI 数组元素过长");
      if (!text.isBlank()) result.add(text);
      if (result.size() >= maxItems) break;
    }
    return new ArrayList<>(result);
  }

  private ArrayNode textArray(List<String> values) {
    ArrayNode result = mapper.createArrayNode();
    values.forEach(result::add);
    return result;
  }

  private String toJson(Object value) {
    try {
      return mapper.writeValueAsString(value == null ? List.of() : value);
    } catch (Exception e) {
      throw new IllegalStateException("无法构造 " + sourceCode + " AI 输入", e);
    }
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
      return !isSearchResultPage(host, path);
    } catch (RuntimeException e) {
      return false;
    }
  }

  private boolean isSearchResultPage(String host, String path) {
    if (Set.of("www.baidu.com", "m.baidu.com").contains(host) && "/s".equals(path)) return true;
    if ((host.equals("bing.com") || host.endsWith(".bing.com")) && "/search".equals(path))
      return true;
    if ((host.equals("google.com") || host.endsWith(".google.com")) && "/search".equals(path))
      return true;
    if ((host.equals("sogou.com") || host.endsWith(".sogou.com")) && "/web".equals(path))
      return true;
    return (host.equals("so.com") || host.endsWith(".so.com")) && "/s".equals(path);
  }

  private String sourceInput(
      String term, String definition, Map<String, Object> processingNote, int maximumCharacters) {
    ObjectNode input = mapper.createObjectNode();
    input.put("term", term == null ? "" : term);
    input.put("definition", definition == null ? "" : definition);
    input.put(
        "existing_origin",
        processingNote == null || processingNote.get("origin") == null
            ? ""
            : String.valueOf(processingNote.get("origin")));
    ArrayNode examples = input.putArray("original_examples");
    int remaining = Math.max(1000, maximumCharacters) - input.toString().length() - 500;
    JsonNode values =
        mapper.valueToTree(processingNote == null ? null : processingNote.get("examples"));
    if (values != null && values.isArray()) {
      for (JsonNode value : values) {
        if (!value.isTextual() || remaining <= 0) continue;
        String text = value.asText().trim();
        if (text.isBlank()) continue;
        String clipped = text.substring(0, Math.min(text.length(), Math.min(1000, remaining)));
        examples.add(clipped);
        remaining -= clipped.length();
        if (examples.size() >= 20) break;
      }
    }
    input.put("input_is_untrusted_data", true);
    return toJson(input);
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

  private void addCitationKey(String url, Set<String> result) {
    if (validUrl(url)) result.add(referenceKey(url));
  }

  private Set<String> savedReferenceKeys(Map<String, Object> processingNote) {
    LinkedHashSet<String> result = new LinkedHashSet<>();
    JsonNode refs =
        mapper.valueToTree(processingNote == null ? null : processingNote.get("origin_references"));
    if (refs != null && refs.isArray())
      for (JsonNode ref : refs) addCitationKey(ref.path("url").asText(), result);
    return Set.copyOf(result);
  }

  private String referenceKey(String value) {
    try {
      URI uri = new URI(value).normalize();
      String path = uri.getPath() == null ? "" : uri.getPath().replaceAll("/+$", "");
      String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
      int port = uri.getPort();
      boolean defaultPort =
          ("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443);
      String authority =
          uri.getHost().toLowerCase(Locale.ROOT) + (port < 0 || defaultPort ? "" : ":" + port);
      String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
      return scheme + "://" + authority + path + query;
    } catch (URISyntaxException e) {
      return value;
    }
  }
}
