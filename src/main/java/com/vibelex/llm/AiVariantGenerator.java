package com.vibelex.llm;

import com.vibelex.candidatediscovery.domain.TermNormalizer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class AiVariantGenerator {
  private static final Logger log = LoggerFactory.getLogger(AiVariantGenerator.class);
  public static final String SCENARIO = "variant-generation";
  private static final int MAX_VARIANTS = 3;
  private static final Set<String> ALLOWED_TYPES =
      Set.of("alias", "abbreviation", "pinyin", "homophone", "typo_variant");

  private final LlmScenarioProperties properties;
  private final PromptTemplateLoader prompts;
  private final ObjectMapper mapper;
  private final TermNormalizer normalizer;
  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

  public AiVariantGenerator(
      LlmScenarioProperties properties,
      PromptTemplateLoader prompts,
      ObjectMapper mapper,
      TermNormalizer normalizer) {
    this.properties = properties;
    this.prompts = prompts;
    this.mapper = mapper;
    this.normalizer = normalizer;
  }

  public boolean isEnabled() {
    return properties.scenario(SCENARIO).isEnabled();
  }

  public List<GeneratedVariant> generate(String term, String definition) {
    LlmScenarioProperties.Scenario scenario = properties.scenario(SCENARIO);
    String baseUrl = required(scenario.getBaseUrl(), "base-url").replaceAll("/+$", "");
    String prompt = renderPrompt(prompts.load(scenario.getPrompt()), term, definition);
    Map<String, Object> body =
        Map.of(
            "model", required(scenario.getModel(), "model"),
            "temperature", scenario.getTemperature(),
            "instructions", prompt,
            "input", "请依据以上规则处理当前词条并返回 JSON 结果。",
            "tools",
                List.of(
                    Map.of(
                        "type",
                        "web_search",
                        "max_keyword",
                        Math.max(1, Math.min(50, scenario.getWebSearchMaxKeyword())))));
    try {
      long startedAt = System.nanoTime();
      log.info("开始调用 Responses 联网变体生成，model={}", scenario.getModel());
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(baseUrl + "/responses"))
              .timeout(Duration.ofSeconds(Math.max(1, scenario.getRequestTimeoutSeconds())))
              .header("Authorization", "Bearer " + required(scenario.getApiKey(), "api-key"))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
              .build();
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        log.warn(
            "Responses 联网变体生成失败，status={}, body={}",
            response.statusCode(),
            abbreviate(response.body(), 500));
        throw new IllegalStateException("LLM 返回 HTTP " + response.statusCode());
      }
      log.debug("Responses 联网变体生成原始响应：{}", response.body());
      JsonNode responseJson = mapper.readTree(response.body());
      int searchCount = responseJson.path("usage").path("tool_usage").path("web_search").asInt();
      if (searchCount < 1) {
        log.warn(
            "Responses 联网变体生成未触发搜索，model={}, elapsedMs={}",
            scenario.getModel(),
            elapsedMillis(startedAt));
        return List.of();
      }
      List<SearchEvidence> citations = citations(responseJson);
      if (citations.isEmpty()) {
        log.warn(
            "Responses 联网变体生成未返回有效结构化引用，丢弃结果，model={}, elapsedMs={}",
            scenario.getModel(),
            elapsedMillis(startedAt));
        return List.of();
      }
      String content = responseText(responseJson);
      List<GeneratedVariant> generated = parse(content, term, citations);
      log.info(
          "Responses 联网变体生成完成，model={}, webSearchCount={}, citationCount={}, variantCount={}, elapsedMs={}",
          scenario.getModel(),
          searchCount,
          citations.size(),
          generated.size(),
          elapsedMillis(startedAt));
      return generated;
    } catch (IOException e) {
      log.warn("Responses 联网变体生成网络请求失败", e);
      throw new IllegalStateException("LLM 变体生成请求失败", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.warn("Responses 联网变体生成请求被中断", e);
      throw new IllegalStateException("LLM 变体生成请求被中断", e);
    } catch (RuntimeException e) {
      log.warn("Responses 联网变体生成处理失败: {}", e.getMessage(), e);
      throw e;
    }
  }

  private List<GeneratedVariant> parse(String content, String term, List<SearchEvidence> citations)
      throws IOException {
    String json = content.trim();
    if (json.startsWith("```"))
      json = json.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
    JsonNode root = mapper.readTree(json);
    JsonNode values = root.path("variants");
    if (!values.isArray()) throw new IllegalArgumentException("LLM 变体输出必须包含 variants 数组");
    String original = normalizer.normalize(term, "zh-CN");
    Set<String> seen = new LinkedHashSet<>();
    List<GeneratedVariant> result = new ArrayList<>();
    for (JsonNode node : values) {
      String value = node.path("variant").asText().trim();
      String type = node.path("variant_type").asText().trim();
      if (value.isBlank() || value.length() > 255 || !ALLOWED_TYPES.contains(type)) continue;
      String normalized = normalizer.normalize(value, "zh-CN", normalizer.profileForVariant(type));
      if (normalized.equals(original) || !seen.add(type + "\u0000" + normalized)) continue;
      if (result.size() >= MAX_VARIANTS) break;
      BigDecimal confidence =
          node.path("confidence").isNumber()
              ? node.path("confidence").decimalValue()
              : BigDecimal.ONE;
      result.add(
          new GeneratedVariant(
              value, type, confidence.max(BigDecimal.ZERO).min(BigDecimal.ONE), citations));
    }
    return result;
  }

  private String responseText(JsonNode response) {
    String outputText = response.path("output_text").asText();
    if (!outputText.isBlank()) return outputText;
    StringBuilder text = new StringBuilder();
    for (JsonNode output : response.path("output")) {
      for (JsonNode content : output.path("content")) {
        String value = content.path("text").asText();
        if (!value.isBlank()) text.append(value);
      }
    }
    if (text.length() == 0) throw new IllegalStateException("Responses API 未返回文本结果");
    return text.toString();
  }

  private List<SearchEvidence> citations(JsonNode response) {
    List<SearchEvidence> result = new ArrayList<>();
    collectCitations(response.path("output"), result);
    return result;
  }

  private void collectCitations(JsonNode node, List<SearchEvidence> result) {
    if (node == null || node.isMissingNode() || result.size() >= 2) return;
    if (node.isArray()) {
      for (JsonNode item : node) collectCitations(item, result);
      return;
    }
    if (!node.isObject()) return;

    JsonNode urlCitation = node.path("url_citation");
    if (urlCitation.isObject()) addCitation(urlCitation, node, result);
    if ("url_citation".equals(node.path("type").asText())) addCitation(node, node, result);
    if (isSearchResult(node)) addCitation(node, node, result);

    node.properties().forEach(field -> collectCitations(field.getValue(), result));
  }

  private boolean isSearchResult(JsonNode node) {
    return "web_search_result".equals(node.path("type").asText())
        || "search_result".equals(node.path("type").asText());
  }

  private void addCitation(JsonNode citation, JsonNode fallback, List<SearchEvidence> result) {
    if (result.size() >= 2) return;
    String url = citation.path("url").asText(fallback.path("url").asText()).trim();
    if (!isEvidenceUrl(url) || result.stream().anyMatch(existing -> existing.url().equals(url)))
      return;
    String title = citation.path("title").asText(fallback.path("title").asText()).trim();
    String snippet = citation.path("snippet").asText(fallback.path("snippet").asText()).trim();
    if (snippet.isBlank())
      snippet = citation.path("content").asText(fallback.path("content").asText()).trim();
    result.add(new SearchEvidence(url, title, snippet));
  }

  private boolean isEvidenceUrl(String value) {
    if (value.isBlank() || value.length() > 2048) return false;
    try {
      URI uri = new URI(value);
      if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null) return false;
      String path = uri.getPath() == null ? "" : uri.getPath();
      if (path.isBlank() || "/".equals(path)) return false;
      String host = uri.getHost().toLowerCase(Locale.ROOT);
      return !(Set.of("www.baidu.com", "m.baidu.com", "www.bing.com", "cn.bing.com").contains(host)
          && Set.of("/s", "/search").contains(path));
    } catch (URISyntaxException exception) {
      return false;
    }
  }

  private String renderPrompt(String template, String term, String definition) {
    if (!template.contains("{{term}}") || !template.contains("{{definition}}")) {
      throw new IllegalStateException("LLM 提示词文件必须包含 {{term}} 和 {{definition}} 占位符");
    }
    return template
        .replace("{{term}}", term == null ? "" : term)
        .replace("{{definition}}", definition == null ? "" : definition);
  }

  private String required(String value, String field) {
    if (value == null || value.isBlank()) throw new IllegalStateException("LLM 场景缺少配置: " + field);
    return value.trim();
  }

  private long elapsedMillis(long startedAt) {
    return Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
  }

  private String abbreviate(String value, int maxLength) {
    if (value == null) return "";
    return value.length() <= maxLength ? value : value.substring(0, maxLength) + "…";
  }

  public record GeneratedVariant(
      String variant, String variantType, BigDecimal confidence, List<SearchEvidence> evidence) {}

  public record SearchEvidence(String url, String title, String snippet) {}
}
