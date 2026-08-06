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

class BuzzwordAiEnricherTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private final PromptTemplateLoader prompts = mock(PromptTemplateLoader.class);
  private final ResponsesWebSearchLlmClient client = mock(ResponsesWebSearchLlmClient.class);
  private BuzzwordAiEnricher enricher;

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
    scenario.setPrompt("classpath:test-prompt.md");
    scenario.setTemperature(new BigDecimal("0.1"));
    scenario.setWebSearchMaxKeyword(2);
    scenario.setMinimumConfidence(new BigDecimal("0.6"));
    properties.setScenarios(Map.of("buzzword-enrichment", scenario));
    when(prompts.load("classpath:test-prompt.md")).thenReturn("system rules");
    enricher = new BuzzwordAiEnricher(properties, prompts, client, mapper);
  }

  @Test
  void enrichesOriginReferencesAndExactlyThreeExamples() throws Exception {
    String output =
        """
        {"origin":"起源于某公开页面的固定表达。","origin_references":[{"title":"参考页面","url":"https://example.com/article"}],"examples":["这条消息让我当场破防。","看到结局时，大家都说自己破防了。","别再发这张图了，我真的要破防了。"],"confidence":0.86,"needs_review":false,"issues":[]}
        """;
    when(client.completeWebSearch(any(), anyInt()))
        .thenReturn(
            new ResponsesWebSearchLlmClient.ResponsesResult(
                output, searchResponse("https://example.com/article")));

    ImportRecordEnricher.EnrichedRecord result =
        enricher.enrich("破防", "情绪受到强烈触动", Map.of("examples", List.of("我破防了")));

    assertThat(result.processingNote().get("origin")).isEqualTo("起源于某公开页面的固定表达。");
    assertThat((List<?>) result.processingNote().get("examples")).hasSize(3);
    assertThat((List<?>) result.processingNote().get("origin_references")).hasSize(1);
    assertThat(result.provider()).isEqualTo("search");
    ArgumentCaptor<LlmRequest> request = ArgumentCaptor.forClass(LlmRequest.class);
    verify(client).completeWebSearch(request.capture(), anyInt());
    assertThat(request.getValue().systemPrompt()).isEqualTo("system rules");
    assertThat(request.getValue().userContent()).contains("\"term\":\"破防\"");
  }

  @Test
  void rejectsExamplesThatDoNotUseTheCurrentTerm() throws Exception {
    String output =
        """
        {"origin":"","origin_references":[],"examples":["我很难过。","大家都很感动。","这条消息很意外。"],"confidence":0.7,"needs_review":true,"issues":["来源信息不足"]}
        """;
    when(client.completeWebSearch(any(), anyInt()))
        .thenReturn(new ResponsesWebSearchLlmClient.ResponsesResult(output, searchResponse()));

    assertThatThrownBy(() -> enricher.enrich("破防", "情绪受到强烈触动", Map.of("examples", List.of())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("必须使用当前词条");
  }

  @Test
  void rejectsMoreThanThreeOriginReferences() throws Exception {
    String output =
        """
        {"origin":"起源说明。","origin_references":[{"title":"一","url":"https://example.com/1"},{"title":"二","url":"https://example.com/2"},{"title":"三","url":"https://example.com/3"},{"title":"四","url":"https://example.com/4"}],"examples":["这件事让我破防了。","看到这里，大家都说自己破防了。","别再说了，我真的要破防了。"],"confidence":0.8,"needs_review":false,"issues":[]}
        """;
    when(client.completeWebSearch(any(), anyInt()))
        .thenReturn(
            new ResponsesWebSearchLlmClient.ResponsesResult(
                output,
                searchResponse(
                    "https://example.com/1",
                    "https://example.com/2",
                    "https://example.com/3",
                    "https://example.com/4")));

    assertThatThrownBy(() -> enricher.enrich("破防", "情绪受到强烈触动", Map.of("examples", List.of())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("最多只能包含 3 条");
  }

  @Test
  void rejectsOriginReferenceThatWasNotReturnedByWebSearch() throws Exception {
    String output =
        """
        {"origin":"起源说明。","origin_references":[{"title":"伪造来源","url":"https://unrelated.example/article"}],"examples":["这件事让我破防了。","看到这里，大家都说自己破防了。","别再说了，我真的要破防了。"],"confidence":0.8,"needs_review":false,"issues":[]}
        """;
    when(client.completeWebSearch(any(), anyInt()))
        .thenReturn(
            new ResponsesWebSearchLlmClient.ResponsesResult(
                output, searchResponse("https://example.com/real-article")));

    assertThatThrownBy(() -> enricher.enrich("破防", "情绪受到强烈触动", Map.of("examples", List.of())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不在本次 web_search 引用结果中");
  }

  @Test
  void doesNotTreatDifferentQueryAsTheSameSearchCitation() throws Exception {
    String output =
        """
        {"origin":"起源说明。","origin_references":[{"title":"伪造来源","url":"https://example.com/article?section=other"}],"examples":["这件事让我破防了。","看到这里，大家都说自己破防了。","别再说了，我真的要破防了。"],"confidence":0.8,"needs_review":false,"issues":[]}
        """;
    when(client.completeWebSearch(any(), anyInt()))
        .thenReturn(
            new ResponsesWebSearchLlmClient.ResponsesResult(
                output, searchResponse("https://example.com/article?section=origin")));

    assertThatThrownBy(() -> enricher.enrich("破防", "情绪受到强烈触动", Map.of("examples", List.of())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不在本次 web_search 引用结果中");
  }

  @Test
  void stillEnrichesExamplesWhenNoReliableOriginIsFound() throws Exception {
    String output =
        """
        {"origin":"","origin_references":[],"examples":["这个普通名称最近成了大家常用的调侃。","群里又有人用普通名称回应这件事。","看到这段对话，我才明白普通名称该怎么用。"],"confidence":0.58,"needs_review":true,"issues":["来源信息不足"]}
        """;
    when(client.completeWebSearch(any(), anyInt()))
        .thenReturn(new ResponsesWebSearchLlmClient.ResponsesResult(output, searchResponse()));

    ImportRecordEnricher.EnrichedRecord result =
        enricher.enrich("普通名称", "一个普通名称", Map.of("examples", List.of()));

    assertThat(result.ignored()).isFalse();
    assertThat((List<?>) result.processingNote().get("examples")).hasSize(3);
    assertThat(((Map<?, ?>) result.processingNote().get("ai_enrichment")).get("needs_review"))
        .isEqualTo(true);
  }

  private tools.jackson.databind.JsonNode searchResponse(String... urls) throws Exception {
    var root = mapper.createObjectNode();
    root.putObject("usage").putObject("tool_usage").put("web_search", 1);
    var annotations =
        root.putArray("output").addObject().putArray("content").addObject().putArray("annotations");
    for (String url : urls) annotations.addObject().put("type", "url_citation").put("url", url);
    return root;
  }
}
