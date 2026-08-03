package com.vibelex.llm;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class ResponsesWebSearchLlmClient implements LlmClient {
  private final ObjectMapper mapper;
  private final LlmScenarioProperties properties;
  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

  public ResponsesWebSearchLlmClient(ObjectMapper mapper, LlmScenarioProperties properties) {
    this.mapper = mapper;
    this.properties = properties;
  }

  @Override
  public String protocol() {
    return "responses";
  }

  @Override
  public String complete(LlmRequest request) {
    return completeWebSearch(request, 2).text();
  }

  public ResponsesResult completeWebSearch(LlmRequest request, int maximumKeywords) {
    try {
      LlmScenarioProperties.Provider provider = provider(request.model());
      Map<String, Object> body =
          Map.of(
              "model", required(request.model(), "model"),
              "temperature", request.temperature(),
              "instructions", request.systemPrompt(),
              "input", request.userContent(),
              "tools",
                  List.of(
                      Map.of(
                          "type",
                          "web_search",
                          "max_keyword",
                          Math.max(1, Math.min(50, maximumKeywords)))));
      HttpRequest httpRequest =
          HttpRequest.newBuilder()
              .uri(
                  URI.create(
                      required(provider.getBaseUrl(), "base-url").replaceAll("/+$", "")
                          + "/responses"))
              .timeout(Duration.ofSeconds(Math.max(1, request.requestTimeoutSeconds())))
              .header("Authorization", "Bearer " + required(provider.getApiKey(), "api-key"))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
              .build();
      HttpResponse<String> response =
          client.send(httpRequest, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() / 100 != 2)
        throw new IllegalStateException("Responses API 返回 HTTP " + response.statusCode());
      JsonNode root = mapper.readTree(response.body());
      String text = root.path("output_text").asText("").trim();
      if (text.isBlank()) {
        StringBuilder value = new StringBuilder();
        for (JsonNode output : root.path("output"))
          for (JsonNode content : output.path("content")) {
            String part = content.path("text").asText("");
            if (!part.isBlank()) value.append(part);
          }
        text = value.toString();
      }
      if (text.isBlank()) throw new IllegalStateException("Responses API 未返回文本结果");
      return new ResponsesResult(text, root);
    } catch (HttpTimeoutException e) {
      throw new IllegalStateException(
          "Responses API 请求超时（" + request.requestTimeoutSeconds() + " 秒）", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Responses API 请求被中断", e);
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("Responses API 请求失败", e);
    }
  }

  private LlmScenarioProperties.Provider provider(String model) {
    return properties.getProviders().values().stream()
        .filter(item -> protocol().equals(item.getProtocol()) && model.equals(item.getModel()))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("找不到模型对应的 Responses provider: " + model));
  }

  private String required(String value, String field) {
    if (value == null || value.isBlank())
      throw new IllegalStateException("LLM provider 缺少配置: " + field);
    return value.trim();
  }

  public record ResponsesResult(String text, JsonNode response) {}
}
