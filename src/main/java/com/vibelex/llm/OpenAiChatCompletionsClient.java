package com.vibelex.llm;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class OpenAiChatCompletionsClient implements LlmClient {
  private final ObjectMapper mapper;
  private final LlmScenarioProperties properties;
  private final HttpClient client;

  @Autowired
  public OpenAiChatCompletionsClient(ObjectMapper mapper, LlmScenarioProperties properties) {
    this(
        mapper, properties, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
  }

  OpenAiChatCompletionsClient(
      ObjectMapper mapper, LlmScenarioProperties properties, HttpClient client) {
    this.mapper = mapper;
    this.properties = properties;
    this.client = client;
  }

  @Override
  public String protocol() {
    return "chat-completions";
  }

  @Override
  public String complete(LlmRequest request) {
    required(request.model(), "model");
    LlmScenarioProperties.Provider provider = provider(request.model());
    try {
      Map<String, Object> body =
          Map.of(
              "model", request.model(),
              "temperature", request.temperature(),
              "messages",
                  List.of(
                      Map.of("role", "system", "content", request.systemPrompt()),
                      Map.of("role", "user", "content", request.userContent())));
      HttpRequest httpRequest =
          HttpRequest.newBuilder()
              .uri(
                  URI.create(
                      required(provider.getBaseUrl(), "base-url").replaceAll("/+$", "")
                          + "/chat/completions"))
              .timeout(Duration.ofSeconds(Math.max(1, request.requestTimeoutSeconds())))
              .header("Authorization", "Bearer " + required(provider.getApiKey(), "api-key"))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
              .build();
      HttpResponse<String> response =
          client.send(httpRequest, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() / 100 != 2) {
        throw new IllegalStateException("Chat Completions 返回 HTTP " + response.statusCode());
      }
      JsonNode root = mapper.readTree(response.body());
      JsonNode choices = root.path("choices");
      if (!choices.isArray() || choices.isEmpty()) {
        throw new IllegalStateException("Chat Completions 未返回 choices");
      }
      String content = choices.get(0).path("message").path("content").asText("").trim();
      if (content.isBlank()) throw new IllegalStateException("Chat Completions 未返回文本结果");
      return content;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Chat Completions 请求被中断", e);
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("Chat Completions 请求失败", e);
    }
  }

  private LlmScenarioProperties.Provider provider(String model) {
    return properties.getProviders().values().stream()
        .filter(item -> protocol().equals(item.getProtocol()) && model.equals(item.getModel()))
        .findFirst()
        .orElseThrow(
            () -> new IllegalStateException("找不到模型对应的 Chat Completions provider: " + model));
  }

  private String required(String value, String field) {
    if (value == null || value.isBlank())
      throw new IllegalStateException("LLM provider 缺少配置: " + field);
    return value.trim();
  }
}
