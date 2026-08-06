package com.vibelex.crawling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.vibelex.candidatediscovery.domain.TermNormalizer;
import com.vibelex.crawling.CrawlConnector.FetchedCrawlEntry;
import com.vibelex.llm.LlmScenarioProperties;
import com.vibelex.llm.PromptTemplateLoader;
import com.vibelex.llm.ResponsesWebSearchLlmClient;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class PopCidianAiEnricherTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private final PromptTemplateLoader prompts = mock(PromptTemplateLoader.class);
  private final ResponsesWebSearchLlmClient client = mock(ResponsesWebSearchLlmClient.class);
  private PopCidianAiEnricher enricher;

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
    scenario.setPrompt("classpath:popcidian-prompt.md");
    scenario.setTemperature(new BigDecimal("0.1"));
    scenario.setWebSearchMaxKeyword(2);
    scenario.setMinimumConfidence(new BigDecimal("0.6"));
    properties.setScenarios(Map.of("popcidian-enrichment", scenario));
    when(prompts.load("classpath:popcidian-prompt.md")).thenReturn("system rules");
    enricher = new PopCidianAiEnricher(properties, prompts, client, mapper, new TermNormalizer());
  }

  @Test
  void preservesPopCidianExamplesAndOnlyFillsTheMissingCount() {
    String output =
        """
        {"origin":"经核验的起源。","origin_references":[{"title":"起源页面","url":"https://example.com/origin"}],"examples":["看到这个结果，大家都说测试梗太贴切了。"],"confidence":0.88,"needs_review":false,"issues":[]}
        """;
    when(client.completeWebSearch(any(), anyInt()))
        .thenReturn(
            new ResponsesWebSearchLlmClient.ResponsesResult(
                output, searchResponse("https://example.com/origin")));
    FetchedCrawlEntry source = source(List.of("波普原句一", "波普原句二"));

    CrawlEntryProcessor.ProcessedEntry result = enricher.process(source);

    assertThat(result.entry().examples()).containsExactly("波普原句一", "波普原句二", "看到这个结果，大家都说测试梗太贴切了。");
    assertThat(result.entry().definition()).isEqualTo("测试释义");
    assertThat(result.entry().origin()).isEqualTo("经核验的起源。");
    assertThat(result.entry().originReferences()).hasSize(1);
    assertThat(result.ignoredReason()).isNull();
  }

  @Test
  void keepsThreeOriginalExamplesWithoutGeneratingAny() {
    String output =
        """
        {"origin":"","origin_references":[],"examples":[],"confidence":0.5,"needs_review":true,"issues":["来源信息不足"]}
        """;
    when(client.completeWebSearch(any(), anyInt()))
        .thenReturn(new ResponsesWebSearchLlmClient.ResponsesResult(output, searchResponse()));
    FetchedCrawlEntry source = source(List.of("波普原句一", "波普原句二", "波普原句三"));

    CrawlEntryProcessor.ProcessedEntry result = enricher.process(source);

    assertThat(result.entry().examples()).containsExactly("波普原句一", "波普原句二", "波普原句三");
    assertThat(result.entry().needsReview()).isTrue();
  }

  @Test
  void keepsAllOriginalExamplesWhenPopCidianAlreadyProvidesMoreThanThree() {
    String output =
        """
        {"origin":"","origin_references":[],"examples":[],"confidence":0.5,"needs_review":true,"issues":["来源信息不足"]}
        """;
    when(client.completeWebSearch(any(), anyInt()))
        .thenReturn(new ResponsesWebSearchLlmClient.ResponsesResult(output, searchResponse()));
    FetchedCrawlEntry source = source(List.of("原句一", "原句二", "原句三", "原句四"));

    CrawlEntryProcessor.ProcessedEntry result = enricher.process(source);

    assertThat(result.entry().examples()).containsExactly("原句一", "原句二", "原句三", "原句四");
  }

  @Test
  void acceptsAnyNormalizedFormFromACompoundTerm() {
    String output =
        """
        {"origin":"","origin_references":[],"examples":["这下是真的妹得办法了。","问了一圈还是妹有结果。"],"confidence":0.7,"needs_review":false,"issues":[]}
        """;
    when(client.completeWebSearch(any(), anyInt()))
        .thenReturn(new ResponsesWebSearchLlmClient.ResponsesResult(output, searchResponse()));
    FetchedCrawlEntry source = source("妹得、妹有", List.of("原来妹得是这个意思。"));

    CrawlEntryProcessor.ProcessedEntry result = enricher.process(source);

    assertThat(result.entry().examples())
        .containsExactly("原来妹得是这个意思。", "这下是真的妹得办法了。", "问了一圈还是妹有结果。");
    assertThat(result.entry().issues()).doesNotContain("AI补充例句不完整");
  }

  @Test
  void normalizesTraditionalCharactersAndLetterCaseWhenCheckingExamples() {
    String output =
        """
        {"origin":"","origin_references":[],"examples":["大家都劝他多喝热水。","最近他又开始玩GTA了。"],"confidence":0.7,"needs_review":false,"issues":[]}
        """;
    when(client.completeWebSearch(any(), anyInt()))
        .thenReturn(new ResponsesWebSearchLlmClient.ResponsesResult(output, searchResponse()));
    FetchedCrawlEntry source = source("多喝熱水、gta", List.of("多喝熱水只是随口一说。"));

    CrawlEntryProcessor.ProcessedEntry result = enricher.process(source);

    assertThat(result.entry().examples()).hasSize(3);
    assertThat(result.entry().issues()).doesNotContain("AI补充例句不完整");
  }

  @Test
  void keepsVerifiedOriginWhenGeneratedExamplesAreNotUsable() {
    String output =
        """
        {"origin":"经核验的起源。","origin_references":[{"title":"起源页面","url":"https://example.com/origin"}],"examples":["这款游戏虽然一直折磨我，但我还是舍不得放下。","这个软件问题很多，我依然每天使用。","这支队伍总让人失望，粉丝还是继续支持。"],"confidence":0.8,"needs_review":false,"issues":[]}
        """;
    when(client.completeWebSearch(any(), anyInt()))
        .thenReturn(
            new ResponsesWebSearchLlmClient.ResponsesResult(
                output, searchResponse("https://example.com/origin")));
    FetchedCrawlEntry source = source("xx虐我千百遍，我待xx如初恋", List.of());

    CrawlEntryProcessor.ProcessedEntry result = enricher.process(source);

    assertThat(result.entry().origin()).isEqualTo("经核验的起源。");
    assertThat(result.entry().originReferences()).hasSize(1);
    assertThat(result.entry().examples()).isEmpty();
    assertThat(result.entry().needsReview()).isTrue();
    assertThat(result.entry().issues()).contains("AI补充例句不完整");
  }

  @Test
  void rejectsAiValidityJudgements() {
    String output =
        """
        {"is_valid_meme":false,"origin":"","origin_references":[],"examples":[],"confidence":0.5,"needs_review":true,"issues":[]}
        """;
    when(client.completeWebSearch(any(), anyInt()))
        .thenReturn(new ResponsesWebSearchLlmClient.ResponsesResult(output, searchResponse()));

    assertThatThrownBy(() -> enricher.process(source(List.of("一", "二", "三"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("未知字段: is_valid_meme");
  }

  private FetchedCrawlEntry source(List<String> examples) {
    return source("测试梗", examples);
  }

  private FetchedCrawlEntry source(String term, List<String> examples) {
    return new FetchedCrawlEntry(
        term,
        "测试释义",
        "",
        examples,
        "网络流行语",
        List.of("互联网"),
        "https://www.popcidian.com/entry/test",
        term,
        Instant.EPOCH,
        "popcidian-api-v1");
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
