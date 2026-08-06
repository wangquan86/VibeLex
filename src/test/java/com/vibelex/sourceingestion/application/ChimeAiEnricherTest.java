package com.vibelex.sourceingestion.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vibelex.llm.LlmRequest;
import com.vibelex.llm.LlmScenarioProperties;
import com.vibelex.llm.PromptTemplateLoader;
import com.vibelex.llm.ResponsesWebSearchLlmClient;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

class ChimeAiEnricherTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private final PromptTemplateLoader prompts = mock(PromptTemplateLoader.class);
  private final ResponsesWebSearchLlmClient client = mock(ResponsesWebSearchLlmClient.class);
  private ChimeAiEnricher enricher;

  @BeforeEach
  void setUp() {
    LlmScenarioProperties properties = new LlmScenarioProperties();
    LlmScenarioProperties.Provider provider = new LlmScenarioProperties.Provider();
    provider.setProtocol("responses");
    provider.setBaseUrl("https://example.test");
    provider.setApiKey("test");
    provider.setModel("test-model");
    properties.setProviders(Map.of("search", provider));
    LlmScenarioProperties.Scenario scenario = new LlmScenarioProperties.Scenario();
    scenario.setEnabled(true);
    scenario.setProvider("search");
    scenario.setPrompt("classpath:chime-prompt.md");
    scenario.setTemperature(new BigDecimal("0.1"));
    scenario.setWebSearchMaxKeyword(2);
    scenario.setMinimumConfidence(new BigDecimal("0.6"));
    properties.setScenarios(Map.of("chime-enrichment", scenario));
    when(prompts.load("classpath:chime-prompt.md")).thenReturn("system rules");
    enricher = new ChimeAiEnricher(properties, prompts, client, mapper);
  }

  @Test
  void enrichesOriginWhilePreservingOriginalExamplesExactly() throws Exception {
    String output =
        """
        {"origin":"经核验的起源说明。","origin_references":[{"title":"原始页面","url":"https://example.com/article"}],"confidence":0.9,"needs_review":false,"issues":[]}
        """;
    when(client.completeWebSearch(any(), anyInt()))
        .thenReturn(
            new ResponsesWebSearchLlmClient.ResponsesResult(
                output, searchResponse("https://example.com/article")));
    List<String> originalExamples = List.of("原始例句一", "原始例句二", "原始例句三");

    ImportRecordEnricher.EnrichedRecord result =
        enricher.enrich("测试梗", "测试释义", Map.of("origin", "待核验起源", "examples", originalExamples));

    assertThat(result.processingNote().get("examples")).isEqualTo(originalExamples);
    assertThat(result.processingNote().get("origin")).isEqualTo("经核验的起源说明。");
    assertThat((List<?>) result.processingNote().get("origin_references")).hasSize(1);
    assertThat(result.processorVersion()).isEqualTo("chime-ai-enrichment-v1");
    ArgumentCaptor<LlmRequest> request = ArgumentCaptor.forClass(LlmRequest.class);
    verify(client).completeWebSearch(request.capture(), anyInt());
    assertThat(request.getValue().userContent())
        .contains("\"existing_origin\":\"待核验起源\"")
        .contains("原始例句一");
  }

  @Test
  void rejectsAnyAiAttemptToReplaceChimeExamples() throws Exception {
    String output =
        """
        {"origin":"","origin_references":[],"examples":["AI 例句"],"confidence":0.5,"needs_review":true,"issues":["来源信息不足"]}
        """;
    when(client.completeWebSearch(any(), anyInt()))
        .thenReturn(new ResponsesWebSearchLlmClient.ResponsesResult(output, searchResponse()));

    assertThatThrownBy(() -> enricher.enrich("测试梗", "测试释义", Map.of("examples", List.of("唯一原始例句"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("未知字段: examples");
  }

  private tools.jackson.databind.JsonNode searchResponse(String... urls) {
    var root = mapper.createObjectNode();
    root.putObject("usage").putObject("tool_usage").put("web_search", 1);
    var annotations =
        root.putArray("output").addObject().putArray("content").addObject().putArray("annotations");
    for (String url : urls) annotations.addObject().put("type", "url_citation").put("url", url);
    return root;
  }
}
