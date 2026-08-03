package com.vibelex.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class OpenAiChatCompletionsClientTest {
  @Test
  void sendsPlainChatCompletionWithoutToolsAndReadsContent() throws Exception {
    AtomicReference<String> requestBody = new AtomicReference<>();
    HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext(
        "/chat/completions",
        exchange -> {
          requestBody.set(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          byte[] response =
              "{\"choices\":[{\"message\":{\"content\":\"{\\\"ok\\\":true}\"}}]}"
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    server.start();
    try {
      LlmScenarioProperties properties = new LlmScenarioProperties();
      LlmScenarioProperties.Provider provider = new LlmScenarioProperties.Provider();
      provider.setProtocol("chat-completions");
      provider.setBaseUrl("http://localhost:" + server.getAddress().getPort());
      provider.setApiKey("secret");
      provider.setModel("model-a");
      properties.setProviders(java.util.Map.of("test", provider));
      var client = new OpenAiChatCompletionsClient(new ObjectMapper(), properties);
      String content =
          client.complete(
              new LlmRequest("model-a", "system rules", "source json", new BigDecimal("0.1"), 5));
      assertThat(content).isEqualTo("{\"ok\":true}");
      assertThat(requestBody.get())
          .contains("\"model\":\"model-a\"", "\"role\":\"system\"", "\"role\":\"user\"")
          .doesNotContain("tools", "web_search", "tool_choice");
    } finally {
      server.stop(0);
    }
  }
}
