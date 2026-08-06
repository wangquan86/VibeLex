package com.vibelex.llm;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ResponsesWebSearchLlmClientTest {

  @Test
  void includesOnlySanitizedProviderErrorDetailsForNonSuccessfulResponses() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/responses",
        exchange -> {
          byte[] body =
              "{\"error\":{\"code\":\"invalid_parameter\",\"type\":\"bad_request\",\"message\":\"unsupported tool; authorization=secret-value\",\"param\":\"tools\"}}"
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.getResponseHeaders().add("x-request-id", "request-123");
          exchange.sendResponseHeaders(400, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    try {
      LlmScenarioProperties properties = new LlmScenarioProperties();
      LlmScenarioProperties.Provider provider = new LlmScenarioProperties.Provider();
      provider.setProtocol("responses");
      provider.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
      provider.setApiKey("api-secret");
      provider.setModel("test-model");
      properties.setProviders(Map.of("test", provider));
      ResponsesWebSearchLlmClient client =
          new ResponsesWebSearchLlmClient(new ObjectMapper(), properties);

      assertThatThrownBy(
              () ->
                  client.completeWebSearch(
                      new LlmRequest(
                          "test-model", "system prompt", "user content", new BigDecimal("0.1"), 10),
                      2))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("HTTP 400")
          .hasMessageContaining("code=invalid_parameter")
          .hasMessageContaining("type=bad_request")
          .hasMessageContaining("param=tools")
          .hasMessageContaining("request_id=request-123")
          .hasMessageContaining("authorization=***")
          .hasMessageNotContaining("secret-value")
          .hasMessageNotContaining("api-secret")
          .hasMessageNotContaining("system prompt")
          .hasMessageNotContaining("user content");
    } finally {
      server.stop(0);
    }
  }
}
