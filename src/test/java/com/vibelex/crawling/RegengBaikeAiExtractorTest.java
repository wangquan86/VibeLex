package com.vibelex.crawling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vibelex.crawling.CrawlConnector.FetchedCrawlEntry;
import com.vibelex.crawling.CrawlConnector.OriginReference;
import com.vibelex.llm.LlmScenarioProperties;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class RegengBaikeAiExtractorTest {
  private RegengBaikeAiExtractor extractor;
  private FetchedCrawlEntry source;

  @BeforeEach
  void setUp() {
    LlmScenarioProperties properties = new LlmScenarioProperties();
    LlmScenarioProperties.Scenario scenario = new LlmScenarioProperties.Scenario();
    scenario.setMinimumConfidence(new BigDecimal("0.6"));
    properties.setScenarios(Map.of(RegengBaikeAiExtractor.SCENARIO, scenario));
    extractor = new RegengBaikeAiExtractor(properties, null, null, new ObjectMapper());
    source =
        new FetchedCrawlEntry(
            "尊嘟假嘟",
            "表示真的假的吗",
            "例句：尊嘟假嘟？其他正文。",
            List.of(),
            "网络流行语",
            List.of(),
            "https://regengbaike.com/1.html",
            "1",
            Instant.EPOCH,
            RegengBaikeConnector.PARSER_VERSION);
  }

  @Test
  void acceptsThreeDistinctExtractedOrGeneratedExamples() {
    var result =
        extractor.parse(
            """
        {"is_valid_meme":true,"definition":"表示‘真的假的’，常用于卖萌式确认。","origin":"",
        "examples":["尊嘟假嘟，你今天就升职了？","这个消息尊嘟假嘟，我怎么没听说？","你说周末放假，尊嘟假嘟？"],"category":"homophone","confidence":0.88,
        "needs_review":false,"issues":[]}
        """,
            source);
    assertThat(result.examples()).hasSize(3).allMatch(example -> example.contains("尊嘟假嘟"));
    assertThat(result.needsReview()).isFalse();
    assertThat(result.issues()).isEmpty();
  }

  @Test
  void usesSourcePageAsOriginReferenceWhenOriginExists() {
    String output =
        "{\"is_valid_meme\":true,\"definition\":\"表示真的假的。\",\"origin\":\"源于网络谐音表达。\",\"examples\":[\"尊嘟假嘟，你今天升职了？\",\"这个消息尊嘟假嘟？\",\"你说周末放假，尊嘟假嘟？\"],\"category\":\"homophone\",\"confidence\":0.8,\"needs_review\":false,\"issues\":[]}";

    var result = extractor.processOutput(source, output, "general-openai", "test-model");

    assertThat(result.entry().originReferences())
        .containsExactly(new OriginReference("热梗百科：尊嘟假嘟", "https://regengbaike.com/1.html"));
    assertThat(result.processorVersion()).isEqualTo("regengbaike-ai-extraction-v3");
  }

  @Test
  void keepsOriginReferencesEmptyWhenOriginIsEmpty() {
    String output =
        "{\"is_valid_meme\":true,\"definition\":\"表示真的假的。\",\"origin\":\"\",\"examples\":[\"尊嘟假嘟，你今天升职了？\",\"这个消息尊嘟假嘟？\",\"你说周末放假，尊嘟假嘟？\"],\"category\":\"homophone\",\"confidence\":0.8,\"needs_review\":false,\"issues\":[]}";

    var result = extractor.processOutput(source, output, "general-openai", "test-model");

    assertThat(result.entry().originReferences()).isEmpty();
  }

  @Test
  void stripsMarkdownFenceButRejectsUnknownFields() {
    String json =
        """
        ```json
        {"is_valid_meme":true,"definition":"释义","origin":"","examples":[],"category":"other",
        "confidence":0.8,"needs_review":false,"issues":[],"invented":true}
        ```
        """;
    assertThatThrownBy(() -> extractor.parse(json, source)).hasMessageContaining("未知字段");
  }

  @Test
  void rejectsIllegalCategoryAndOutOfRangeConfidence() {
    String invalidCategory =
        "{\"is_valid_meme\":true,\"definition\":\"释义\",\"origin\":\"\",\"examples\":[],\"category\":\"invented\",\"confidence\":0.8,\"needs_review\":false,\"issues\":[]}";
    assertThatThrownBy(() -> extractor.parse(invalidCategory, source)).hasMessageContaining("非法分类");
  }

  @Test
  void rejectsValidMemeWithoutExactlyThreeDistinctExamples() {
    String output =
        "{\"is_valid_meme\":true,\"definition\":\"释义\",\"origin\":\"\",\"examples\":[\"例句一\",\"例句二\"],\"category\":\"other\",\"confidence\":0.8,\"needs_review\":false,\"issues\":[]}";

    assertThatThrownBy(() -> extractor.parse(output, source))
        .hasMessageContaining("必须包含 3 条互不重复的例句");
  }
}
