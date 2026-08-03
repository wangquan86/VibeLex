package com.vibelex.crawling;

import static org.assertj.core.api.Assertions.assertThat;

import com.vibelex.crawling.CrawlConnector.CrawlPointer;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class PopCidianConnectorTest {
  private PopCidianConnector connector;
  private ObjectMapper mapper;

  @BeforeEach
  void setUp() {
    CrawlProperties properties = new CrawlProperties();
    mapper = new ObjectMapper();
    connector = new PopCidianConnector(properties, mapper);
  }

  @Test
  void parsesOnlyEntryUrlsAndBuildsMaximumCheckpoint() {
    var result = connector.parseSitemap(sitemap(), null);

    assertThat(result.items())
        .extracting(CrawlPointer::sourceRecordKey)
        .containsExactly("旧词", "新词");
    assertThat(result.nextCheckpoint().path("lastmod").asText()).isEqualTo("2026-07-29T02:00:00Z");
    assertThat(result.nextCheckpoint().path("sourceRecordKey").asText()).isEqualTo("新词");
  }

  @Test
  void incrementalEnumerationUsesCompositeCheckpoint() {
    var checkpoint =
        mapper
            .createObjectNode()
            .put("lastmod", "2026-07-29T01:00:00Z")
            .put("sourceRecordKey", "旧词");

    var result = connector.parseSitemap(sitemap(), checkpoint);

    assertThat(result.items()).extracting(CrawlPointer::sourceRecordKey).containsExactly("新词");
  }

  @Test
  void parsesChineseDefinitionFromEntryResponse() {
    CrawlPointer pointer =
        new CrawlPointer("新词", "https://www.popcidian.com/entry/%E6%96%B0%E8%AF%8D", Instant.EPOCH);
    String json =
        """
        {"result":[{"term":"新词","chineseExplanation":"新的释义",
          "examples":["新词的使用例句"],"category":{"name":"互联网黑话"},
          "tags":[{"name":"互联网"},{"name":"职场"}]}],
         "metadata":{"totalCount":1}}
        """;

    var result = connector.parseEntry(json, pointer);

    assertThat(result.term()).isEqualTo("新词");
    assertThat(result.sourceSummary()).isEqualTo("新的释义");
    assertThat(result.sourceExamples()).containsExactly("新词的使用例句");
    assertThat(PopCidianConnector.category(result.sourceCategory())).isEqualTo("slang");
    assertThat(result.sourceCategory()).isEqualTo("互联网黑话");
    assertThat(result.sourceTags()).containsExactly("互联网", "职场");
    assertThat(result.parserVersion()).isEqualTo("popcidian-api-v1");
  }

  @Test
  void keepsUnknownSourceCategoryButMapsItToOther() {
    CrawlPointer pointer =
        new CrawlPointer("圈内词", "https://www.popcidian.com/entry/test", Instant.EPOCH);
    String json =
        """
        {"result":[{"term":"圈内词","chineseExplanation":"释义",
          "category":{"name":"游戏圈黑话"},"tags":[]}]}
        """;

    var result = connector.parseEntry(json, pointer);

    assertThat(PopCidianConnector.category(result.sourceCategory())).isEqualTo("other");
    assertThat(result.sourceCategory()).isEqualTo("游戏圈黑话");
  }

  private String sitemap() {
    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
          <url><loc>https://www.popcidian.com/entry/%E6%96%B0%E8%AF%8D</loc><lastmod>2026-07-29T02:00:00Z</lastmod></url>
          <url><loc>https://www.popcidian.com/category/test</loc><lastmod>2026-07-29T03:00:00Z</lastmod></url>
          <url><loc>https://www.popcidian.com/entry/%E6%97%A7%E8%AF%8D</loc><lastmod>2026-07-29T01:00:00Z</lastmod></url>
        </urlset>
        """;
  }
}
