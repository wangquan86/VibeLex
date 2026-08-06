package com.vibelex.search;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class BgeEmbeddingProvider implements EmbeddingProvider {
  private static final Logger log = LoggerFactory.getLogger(BgeEmbeddingProvider.class);
  private final SearchProperties properties;
  private final ObjectMapper mapper;
  private final HttpClient client;

  public BgeEmbeddingProvider(SearchProperties properties, ObjectMapper mapper) {
    this.properties = properties;
    this.mapper = mapper;
    this.client =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(properties.getEmbedding().getConnectTimeoutMillis()))
            .build();
  }

  @Override
  public List<Float> embed(String text) {
    SearchProperties.Embedding config = properties.getEmbedding();
    if (!config.isEnabled()) throw new IllegalStateException("embedding 服务未启用");
    try {
      String body =
          mapper.writeValueAsString(Map.of("query", text, "model_name", config.getModelName()));
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(config.getEndpoint()))
              .timeout(Duration.ofMillis(config.getRequestTimeoutMillis()))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(body))
              .build();
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() / 100 != 2)
        throw new IllegalStateException("embedding 服务响应 " + response.statusCode());
      JsonNode root = mapper.readTree(response.body());
      JsonNode vector = root.path("vector");
      if (!vector.isArray()) throw new IllegalStateException("embedding 响应缺少 vector 数组");
      List<Float> values = new ArrayList<>();
      for (JsonNode value : vector) values.add((float) value.asDouble());
      int declared = root.path("dimension").asInt(values.size());
      if (declared != config.getVectorDimension() || values.size() != declared)
        throw new IllegalStateException("embedding 向量维度错误: " + values.size());
      return List.copyOf(values);
    } catch (Exception exception) {
      if (exception instanceof IllegalStateException failure) throw failure;
      log.error("调用 embedding 服务失败 endpoint={}", config.getEndpoint(), exception);
      throw new IllegalStateException(
          "调用 embedding 服务失败: " + exception.getClass().getSimpleName(), exception);
    }
  }
}
