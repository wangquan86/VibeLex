package com.vibelex.recognitionv2;

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
  private final RecognitionV2Properties properties;
  private final ObjectMapper mapper;
  private final HttpClient client;

  public ElasticsearchGateway(RecognitionV2Properties properties, ObjectMapper mapper) {
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

  public boolean indexExists(String index) {
    return request("HEAD", "/" + index, null, false).statusCode() == 200;
  }

  public void createIndex(String index) {
    request("PUT", "/" + index, mapping(), true);
  }

  public void deleteIndex(String index) {
    request("DELETE", "/" + index, null, true);
  }

  public void putAlias(String index, String alias) {
    request(
        "POST",
        "/_aliases",
        Map.of(
            "actions",
            List.of(
                Map.of("remove", Map.of("index", "*", "alias", alias, "must_exist", false)),
                Map.of("add", Map.of("index", index, "alias", alias)))),
        true);
  }

  public void upsert(String index, String id, Map<String, Object> document) {
    request("PUT", "/" + index + "/_doc/" + encode(id), document, true);
  }

  public void delete(String index, String id) {
    request("DELETE", "/" + index + "/_doc/" + encode(id), null, false);
  }

  /** Executes all lexical query units in one ES _msearch round trip. */
  public List<Hit> lexical(Collection<String> queries) {
    if (queries.isEmpty()) return List.of();
    StringBuilder ndjson = new StringBuilder();
    try {
      for (String query : queries) {
        if (query == null || query.isBlank()) continue;
        ndjson.append(
            mapper.writeValueAsString(
                Map.of("index", properties.getElasticsearch().getIndexAlias())));
        ndjson.append('\n');
        ndjson.append(mapper.writeValueAsString(lexicalPayload(query)));
        ndjson.append('\n');
      }
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to build ES lexical multi-search request", ex);
    }
    if (ndjson.isEmpty()) return List.of();
    HttpResponse<String> response = requestMsearch(ndjson.toString());
    try {
      List<Hit> result = new ArrayList<>();
      for (JsonNode item : mapper.readTree(response.body()).path("responses")) {
        if (item.has("error"))
          throw new IllegalStateException(
              "ES lexical multi-search item failed: " + item.path("error"));
        result.addAll(hits(item));
      }
      return result;
    } catch (Exception ex) {
      throw ex instanceof IllegalStateException failure
          ? failure
          : new IllegalStateException("Failed to parse ES lexical multi-search response", ex);
    }
  }

  private Map<String, Object> lexicalPayload(String query) {
    Map<String, Object> multiMatch =
        Map.of(
            "multi_match",
            Map.of(
                "query",
                query,
                "fields",
                List.of("canonical_term^5", "variants^4", "definition^2", "tags^2", "scenes"),
                "type",
                "best_fields"));
    Map<String, Object> bool =
        Map.of(
            "filter",
            List.of(Map.of("term", Map.of("detect_enabled", true))),
            "must",
            List.of(multiMatch));
    return Map.of(
        "size", properties.getElasticsearch().getLexicalTopK(), "query", Map.of("bool", bool));
  }

  public List<Hit> knn(List<Float> vector) {
    Map<String, Object> q =
        Map.of(
            "size",
            properties.getElasticsearch().getSemanticTopK(),
            "knn",
            Map.of(
                "field",
                "embedding",
                "query_vector",
                vector,
                "k",
                properties.getElasticsearch().getSemanticTopK(),
                "num_candidates",
                Math.max(50, properties.getElasticsearch().getSemanticTopK() * 5),
                "filter",
                Map.of("term", Map.of("detect_enabled", true))));
    return search(q);
  }

  public Map<String, Object> status() {
    HttpResponse<String> response =
        request(
            "GET", "/_alias/" + encode(properties.getElasticsearch().getIndexAlias()), null, false);
    return Map.of(
        "enabled",
        enabled(),
        "status",
        response.statusCode(),
        "alias",
        properties.getElasticsearch().getIndexAlias());
  }

  private List<Hit> search(Map<String, Object> payload) {
    HttpResponse<String> response =
        request(
            "POST",
            "/" + properties.getElasticsearch().getIndexAlias() + "/_search",
            payload,
            true);
    try {
      return hits(mapper.readTree(response.body()));
    } catch (Exception e) {
      throw new IllegalStateException("解析 ES 查询响应失败", e);
    }
  }

  private List<Hit> hits(JsonNode response) {
    List<Hit> result = new ArrayList<>();
    for (JsonNode hit : response.path("hits").path("hits")) {
      JsonNode source = hit.path("_source");
      JsonNode sense = source.path("sense_id");
      result.add(
          new Hit(
              source.path("meme_id").asLong(),
              sense.isNull() || sense.isMissingNode() ? null : sense.asLong(),
              source.path("canonical_term").asString(),
              strings(source.path("variants")),
              hit.path("_score").asDouble()));
    }
    return result;
  }

  private HttpResponse<String> requestMsearch(String body) {
    if (!enabled()) throw new IllegalStateException("Elasticsearch is disabled");
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
            "ES lexical multi-search failed " + response.statusCode() + ": " + response.body());
      return response;
    } catch (Exception ex) {
      throw ex instanceof IllegalStateException failure
          ? failure
          : new IllegalStateException("Failed to call Elasticsearch lexical multi-search", ex);
    }
  }

  private List<String> strings(JsonNode node) {
    List<String> r = new ArrayList<>();
    if (node.isArray()) for (JsonNode x : node) r.add(x.asString());
    return r;
  }

  private Map<String, Object> mapping() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("meme_id", Map.of("type", "long"));
    props.put("sense_id", Map.of("type", "long"));
    props.put("meme_code", Map.of("type", "keyword"));
    props.put("canonical_term", analyzedText());
    props.put("variants", analyzedText());
    props.put("definition", analyzedText());
    props.put("tags", analyzedText());
    props.put("scenes", analyzedText());
    props.put("detect_enabled", Map.of("type", "boolean"));
    props.put("risk_level", Map.of("type", "keyword"));
    props.put("indexed_at", Map.of("type", "date"));
    props.put(
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
        Map.of("dynamic", "strict", "properties", props));
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
    } catch (Exception e) {
      throw e instanceof IllegalStateException x
          ? x
          : new IllegalStateException("调用 Elasticsearch 失败", e);
    }
  }

  private String base() {
    String value = properties.getElasticsearch().getUris();
    return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
  }

  private String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  public record Hit(
      long memeId, Long senseId, String canonicalTerm, List<String> variants, double score) {}
}
