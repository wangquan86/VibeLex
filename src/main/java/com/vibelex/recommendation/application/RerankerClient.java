package com.vibelex.recommendation.application;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class RerankerClient {
  private final RecommendationProperties properties;
  private final ObjectMapper mapper;
  private final HttpClient client;

  @Autowired
  public RerankerClient(RecommendationProperties properties, ObjectMapper mapper) {
    this(
        properties,
        mapper,
        HttpClient.newBuilder()
            .connectTimeout(
                Duration.ofMillis(properties.getReranker().getConnectTimeoutMillis()))
            .build());
  }

  RerankerClient(
      RecommendationProperties properties, ObjectMapper mapper, HttpClient client) {
    this.properties = properties;
    this.mapper = mapper;
    this.client = client;
  }

  public boolean enabled() {
    return properties.getReranker().isEnabled();
  }

  public List<Double> rerank(String query, List<String> texts) {
    if (texts.isEmpty()) return List.of();
    try {
      Map<String, Object> body =
          Map.of("query", query, "texts", texts, "return_text", false);
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(rerankUri())
              .timeout(
                  Duration.ofMillis(properties.getReranker().getRequestTimeoutMillis()))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
              .build();
      HttpResponse<String> response =
          client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() / 100 != 2)
        throw new IllegalStateException("Reranker 返回 HTTP " + response.statusCode());
      return scores(mapper.readTree(response.body()), texts.size());
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Reranker 请求被中断", exception);
    } catch (RuntimeException exception) {
      throw exception;
    } catch (Exception exception) {
      throw new IllegalStateException("Reranker 请求失败", exception);
    }
  }

  private URI rerankUri() {
    String endpoint = properties.getReranker().getEndpoint().trim().replaceAll("/+$", "");
    return URI.create(endpoint.endsWith("/rerank") ? endpoint : endpoint + "/rerank");
  }

  private List<Double> scores(JsonNode root, int expected) {
    JsonNode results = root.path("results");
    if (!results.isArray() || results.size() != expected)
      throw new IllegalStateException("Reranker 返回的结果数量不正确");
    List<Double> scores = new ArrayList<>();
    for (int index = 0; index < expected; index++) scores.add(null);
    Set<Integer> seen = new HashSet<>();
    for (JsonNode result : results) {
      int index = result.path("index").asInt(-1);
      double score = result.path("score").asDouble(Double.NaN);
      if (index < 0 || index >= expected || !seen.add(index))
        throw new IllegalStateException("Reranker 返回了无效或重复的候选索引");
      if (!Double.isFinite(score) || score < 0 || score > 1)
        throw new IllegalStateException("Reranker 返回了无效分数");
      scores.set(index, score);
    }
    if (scores.stream().anyMatch(java.util.Objects::isNull))
      throw new IllegalStateException("Reranker 返回结果不完整");
    return List.copyOf(scores);
  }
}
