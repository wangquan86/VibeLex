package com.vibelex.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class RerankerClientTest {
  private HttpServer server;
  private RecommendationProperties properties;

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.start();
    properties = new RecommendationProperties();
    properties
        .getReranker()
        .setEndpoint("http://127.0.0.1:" + server.getAddress().getPort());
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
  }

  @Test
  void mapsScoresByCandidateIndexEvenWhenResponseIsSorted() {
    respond(
        200,
        """
        {"model":"test","results":[{"index":1,"score":0.9},{"index":0,"score":0.1}]}
        """);

    List<Double> scores = client().rerank("query", List.of("first", "second"));

    assertThat(scores).containsExactly(.1, .9);
  }

  @Test
  void rejectsNonSuccessResponse() {
    respond(503, "{\"detail\":\"starting\"}");

    assertThatThrownBy(() -> client().rerank("query", List.of("first")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("HTTP 503");
  }

  @Test
  void rejectsDuplicateOrIncompleteIndexes() {
    respond(
        200,
        """
        {"results":[{"index":0,"score":0.9},{"index":0,"score":0.8}]}
        """);

    assertThatThrownBy(() -> client().rerank("query", List.of("first", "second")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("索引");
  }

  @Test
  void rejectsOutOfRangeScore() {
    respond(200, "{\"results\":[{\"index\":0,\"score\":1.5}]}");

    assertThatThrownBy(() -> client().rerank("query", List.of("first")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("分数");
  }

  private RerankerClient client() {
    return new RerankerClient(properties, new ObjectMapper(), HttpClient.newHttpClient());
  }

  private void respond(int status, String body) {
    server.createContext(
        "/rerank",
        exchange -> {
          drain(exchange);
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(status, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
  }

  private void drain(HttpExchange exchange) throws IOException {
    exchange.getRequestBody().readAllBytes();
  }
}
