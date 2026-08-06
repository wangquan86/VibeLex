package com.vibelex.search;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class ElasticsearchGateway {
  private static final List<String> SOURCE_FIELDS =
      List.of(
          "meme_id",
          "sense_id",
          "sense_no",
          "meme_code",
          "canonical_term",
          "variants",
          "language_code",
          "entry_status",
          "category",
          "domain_tags",
          "definition",
          "examples");
  private final SearchProperties properties;
  private final ObjectMapper mapper;
  private final HttpClient client;

  public ElasticsearchGateway(SearchProperties properties, ObjectMapper mapper) {
    this.properties = properties;
    this.mapper = mapper;
    this.client =
        HttpClient.newBuilder()
            .connectTimeout(
                Duration.ofMillis(properties.getElasticsearch().getConnectTimeoutMillis()))
            .build();
  }

  public boolean enabled() {
    return properties.getElasticsearch().isEnabled();
  }

  public String indexAlias() {
    return properties.getElasticsearch().getIndexAlias();
  }

  public String indexName() {
    return properties.getElasticsearch().getIndexName();
  }

  public boolean indexExists(String index) {
    return request("HEAD", "/" + encode(index), null, false).statusCode() == 200;
  }

  public void createIndex(String index) {
    request("PUT", "/" + encode(index), mapping(), true);
  }

  public void deleteIndex(String index) {
    HttpResponse<String> response = request("DELETE", "/" + encode(index), null, false);
    if (response.statusCode() != 404 && response.statusCode() / 100 != 2)
      throw new IllegalStateException(
          "删除 ES 索引失败 " + response.statusCode() + ": " + response.body());
  }

  public List<String> switchAlias(String nextIndex) {
    List<String> previous = aliasIndices();
    List<Map<String, Object>> actions = new ArrayList<>();
    for (String index : previous)
      actions.add(Map.of("remove", Map.of("index", index, "alias", indexAlias())));
    actions.add(Map.of("add", Map.of("index", nextIndex, "alias", indexAlias())));
    request("POST", "/_aliases", Map.of("actions", actions), true);
    return previous.stream().filter(index -> !index.equals(nextIndex)).toList();
  }

  public boolean aliasExists() {
    return !aliasIndices().isEmpty();
  }

  public boolean aliasCompatible() {
    HttpResponse<String> response =
        request("GET", "/" + encode(indexAlias()) + "/_mapping", null, false);
    if (response.statusCode() == 404) return false;
    if (response.statusCode() / 100 != 2)
      throw new IllegalStateException("读取 ES mapping 失败: " + response.statusCode());
    JsonNode root = parse(response.body(), "解析 ES mapping 失败");
    if (root.propertyNames().isEmpty()) return false;
    for (String index : root.propertyNames())
      if (!"3.2"
          .equals(
              root.path(index).path("mappings").path("_meta").path("schema_version").asString()))
        return false;
    return true;
  }

  public void upsert(String index, String id, Map<String, Object> document) {
    request("PUT", "/" + encode(index) + "/_doc/" + encode(id), document, true);
  }

  public void deleteByMeme(String index, long memeId) {
    Map<String, Object> body = Map.of("query", Map.of("term", Map.of("meme_id", memeId)));
    request(
        "POST",
        "/" + encode(index) + "/_delete_by_query?conflicts=proceed&refresh=true",
        body,
        true);
  }

  /** Executes all V2 lexical query units in one ES round trip. */
  public List<Hit> lexicalForRecognition(Collection<String> queries, int topK) {
    if (queries.isEmpty()) return List.of();
    StringBuilder ndjson = new StringBuilder();
    try {
      for (String query : queries) {
        if (query == null || query.isBlank()) continue;
        ndjson.append(mapper.writeValueAsString(Map.of("index", indexAlias()))).append('\n');
        ndjson
            .append(mapper.writeValueAsString(recognitionLexicalPayload(query, topK)))
            .append('\n');
      }
    } catch (Exception exception) {
      throw new IllegalStateException("构建 ES 词法批量查询失败", exception);
    }
    if (ndjson.isEmpty()) return List.of();
    JsonNode root = parse(requestMsearch(ndjson.toString()).body(), "解析 ES 词法批量查询响应失败");
    List<Hit> result = new ArrayList<>();
    for (JsonNode response : root.path("responses")) {
      if (response.has("error"))
        throw new IllegalStateException("ES 词法批量查询失败: " + response.path("error"));
      result.addAll(hits(response));
    }
    return result;
  }

  public List<Hit> lexicalForRecommendation(String query, int topK) {
    Map<String, Object> multiMatch =
        Map.of(
            "multi_match",
            Map.of(
                "query",
                query,
                "fields",
                // Prefer the term/variants and sense definition; examples are only a weak
                // fallback because incidental wording in them should not drive recommendation.
                List.of(
                    "canonical_term^8",
                    "variants^6",
                    "definition^2",
                    "domain_tags",
                    "examples^0.5"),
                "type",
                "best_fields"));
    return search(
        Map.of(
            "size",
            topK,
            "_source",
            SOURCE_FIELDS,
            "query",
            Map.of(
                "bool",
                Map.of(
                    "filter", publishedChineseFilters(),
                    "must", List.of(multiMatch)))));
  }

  public List<Hit> knnForRecognition(List<Float> vector, int topK) {
    return knn(vector, topK, formalChineseFilter());
  }

  public List<Hit> knnForRecommendation(List<Float> vector, int topK) {
    return knn(vector, topK, Map.of("bool", Map.of("filter", publishedChineseFilters())));
  }

  public Map<String, Object> status() {
    if (!enabled())
      return Map.of(
          "enabled",
          false,
          "status",
          0,
          "alias",
          indexAlias(),
          "indices",
          List.of(),
          "mapping_compatible",
          false);
    HttpResponse<String> response = request("GET", "/_alias/" + encode(indexAlias()), null, false);
    List<String> indices = response.statusCode() == 200 ? aliasIndices() : List.of();
    return Map.of(
        "enabled",
        true,
        "status",
        response.statusCode(),
        "alias",
        indexAlias(),
        "indices",
        indices,
        "mapping_compatible",
        response.statusCode() == 200 && aliasCompatible());
  }

  private List<Hit> knn(List<Float> vector, int topK, Map<String, Object> filter) {
    return search(
        Map.of(
            "size",
            topK,
            "_source",
            SOURCE_FIELDS,
            "knn",
            Map.of(
                "field",
                "embedding",
                "query_vector",
                vector,
                "k",
                topK,
                "num_candidates",
                Math.max(50, topK * 5),
                "filter",
                filter)));
  }

  private Map<String, Object> recognitionLexicalPayload(String query, int topK) {
    Map<String, Object> multiMatch =
        Map.of(
            "multi_match",
            Map.of(
                "query",
                query,
                "fields",
                List.of(
                    "canonical_term^5", "variants^4", "definition^2", "examples^2", "domain_tags"),
                "type",
                "best_fields"));
    return Map.of(
        "size",
        topK,
        "_source",
        SOURCE_FIELDS,
        "query",
        Map.of("bool", Map.of("filter", formalChineseFilters(), "must", List.of(multiMatch))));
  }

  private List<Map<String, Object>> publishedChineseFilters() {
    return List.of(
        Map.of("term", Map.of("entry_status", "published")),
        Map.of("term", Map.of("language_code", "zh-CN")));
  }

  private List<Map<String, Object>> formalChineseFilters() {
    return List.of(
        Map.of("term", Map.of("entry_status", "published")),
        Map.of("term", Map.of("language_code", "zh-CN")));
  }

  private Map<String, Object> formalChineseFilter() {
    return Map.of("bool", Map.of("filter", formalChineseFilters()));
  }

  private List<Hit> search(Map<String, Object> payload) {
    HttpResponse<String> response =
        request("POST", "/" + encode(indexAlias()) + "/_search", payload, true);
    return hits(parse(response.body(), "解析 ES 查询响应失败"));
  }

  private List<Hit> hits(JsonNode response) {
    List<Hit> result = new ArrayList<>();
    for (JsonNode hit : response.path("hits").path("hits")) {
      JsonNode source = hit.path("_source");
      result.add(
          new Hit(
              source.path("meme_id").asLong(),
              nullableLong(source.path("sense_id")),
              source.path("sense_no").asInt(),
              source.path("meme_code").asString(),
              source.path("canonical_term").asString(),
              strings(source.path("variants")),
              source.path("language_code").asString(),
              source.path("entry_status").asString(),
              source.path("category").asString(),
              strings(source.path("domain_tags")),
              source.path("definition").asString(),
              strings(source.path("examples")),
              hit.path("_score").asDouble()));
    }
    return result;
  }

  private Long nullableLong(JsonNode node) {
    return node.isNull() || node.isMissingNode() ? null : node.asLong();
  }

  private List<String> strings(JsonNode node) {
    List<String> result = new ArrayList<>();
    if (node.isArray()) for (JsonNode value : node) result.add(value.asString());
    return List.copyOf(result);
  }

  private List<String> aliasIndices() {
    HttpResponse<String> response = request("GET", "/_alias/" + encode(indexAlias()), null, false);
    if (response.statusCode() == 404) return List.of();
    if (response.statusCode() / 100 != 2)
      throw new IllegalStateException("读取 ES 索引别名失败: " + response.statusCode());
    JsonNode root = parse(response.body(), "解析 ES 索引别名失败");
    List<String> result = new ArrayList<>();
    result.addAll(root.propertyNames());
    return result;
  }

  private JsonNode parse(String value, String message) {
    try {
      return mapper.readTree(value);
    } catch (Exception exception) {
      throw new IllegalStateException(message, exception);
    }
  }

  private Map<String, Object> mapping() {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("meme_id", Map.of("type", "long"));
    fields.put("sense_id", Map.of("type", "long"));
    fields.put("sense_no", Map.of("type", "integer"));
    fields.put("meme_code", Map.of("type", "keyword"));
    fields.put("canonical_term", analyzedText());
    fields.put("variants", analyzedText());
    fields.put("language_code", Map.of("type", "keyword"));
    fields.put("entry_status", Map.of("type", "keyword"));
    fields.put("category", Map.of("type", "keyword"));
    fields.put("domain_tags", analyzedText());
    fields.put("definition", analyzedText());
    fields.put("examples", analyzedText());
    fields.put(
        "embedding",
        Map.of(
            "type",
            "dense_vector",
            "dims",
            properties.getEmbedding().getVectorDimension(),
            "index",
            true,
            "similarity",
            properties.getEmbedding().getSimilarity()));
    return Map.of(
        "settings",
        Map.of("number_of_shards", 1, "number_of_replicas", 0),
        "mappings",
        Map.of(
            "dynamic", "strict", "_meta", Map.of("schema_version", "3.2"), "properties", fields));
  }

  private Map<String, Object> analyzedText() {
    return Map.of(
        "type",
        "text",
        "analyzer",
        "ik_max_word",
        "search_analyzer",
        "ik_smart",
        "fields",
        Map.of("keyword", Map.of("type", "keyword", "ignore_above", 256)));
  }

  private HttpResponse<String> requestMsearch(String body) {
    if (!enabled()) throw new IllegalStateException("Elasticsearch 未启用");
    try {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(base() + "/_msearch"))
              .timeout(Duration.ofMillis(properties.getElasticsearch().getRequestTimeoutMillis()))
              .header("Content-Type", "application/x-ndjson")
              .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
              .build();
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() / 100 != 2)
        throw new IllegalStateException(
            "ES 词法批量查询失败 " + response.statusCode() + ": " + response.body());
      return response;
    } catch (Exception exception) {
      if (exception instanceof IllegalStateException failure) throw failure;
      throw new IllegalStateException("调用 Elasticsearch 词法批量查询失败", exception);
    }
  }

  private HttpResponse<String> request(String method, String path, Object body, boolean required) {
    if (!enabled()) throw new IllegalStateException("Elasticsearch 未启用");
    try {
      HttpRequest.Builder builder =
          HttpRequest.newBuilder(URI.create(base() + path))
              .timeout(Duration.ofMillis(properties.getElasticsearch().getRequestTimeoutMillis()));
      if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
      else
        builder
            .header("Content-Type", "application/json")
            .method(method, HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
      HttpResponse<String> response =
          client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
      if (required && response.statusCode() / 100 != 2)
        throw new IllegalStateException(
            "ES 请求失败 " + response.statusCode() + ": " + response.body());
      return response;
    } catch (Exception exception) {
      if (exception instanceof IllegalStateException failure) throw failure;
      throw new IllegalStateException("调用 Elasticsearch 失败", exception);
    }
  }

  private String base() {
    String value = properties.getElasticsearch().getUris();
    if (value == null || value.isBlank()) throw new IllegalStateException("未配置 Elasticsearch 地址");
    return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
  }

  private String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  public record Hit(
      long memeId,
      Long senseId,
      int senseNo,
      String memeCode,
      String canonicalTerm,
      List<String> variants,
      String languageCode,
      String entryStatus,
      String category,
      List<String> domainTags,
      String definition,
      List<String> examples,
      double score) {}
}
