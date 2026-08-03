package com.vibelex.crawling;

import static org.assertj.core.api.Assertions.assertThat;

import com.vibelex.crawling.CrawlConnector.CrawlPointer;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class RegengBaikeConnectorTest {
  private RegengBaikeConnector connector;
  private ObjectMapper mapper;

  @BeforeEach
  void setUp() {
    CrawlProperties properties = new CrawlProperties();
    mapper = new ObjectMapper();
    connector = new RegengBaikeConnector(properties, mapper);
  }

  @Test
  void acceptsOnlySameHostNumericArchiveUrlsAndSortsById() {
    String xml =
        """
        <urlset>
          <url><loc>https://regengbaike.com/20.html</loc></url>
          <url><loc>https://other.example/30.html</loc></url>
          <url><loc>https://regengbaike.com/about.html</loc></url>
          <url><loc>https://regengbaike.com/3.html</loc></url>
        </urlset>
        """;
    var result = connector.parseSitemap(xml, null);
    assertThat(result.items()).extracting(CrawlPointer::sourceRecordKey).containsExactly("3", "20");
    assertThat(result.nextCheckpoint().path("maximumArchiveId").asLong()).isEqualTo(20);
  }

  @Test
  void enumeratesOnlyIdsAfterCheckpoint() {
    String xml =
        "<urlset><url><loc>https://regengbaike.com/9.html</loc></url><url><loc>https://regengbaike.com/12.html</loc></url></urlset>";
    var result = connector.parseSitemap(xml, mapper.createObjectNode().put("maximumArchiveId", 9));
    assertThat(result.items()).extracting(CrawlPointer::sourceRecordKey).containsExactly("12");
  }

  @Test
  void parsesStructuredPageMaterial() {
    String html =
        """
        <html><head><meta name="description" content=" 页面摘要 "><link rel="canonical" href="https://regengbaike.com/1500.html"></head>
        <body><div class="breadcrumb"><a>首页</a><a>网络流行语</a></div><h1>尊嘟假嘟</h1>
        <time datetime="2023-08-01T10:00:00Z"></time><div class="article-text"><p>正文 第一段。</p><script>bad()</script><p>正文第二段。</p></div></body></html>
        """;
    var result =
        connector.parseEntry(
            html, new CrawlPointer("1500", "https://regengbaike.com/1500.html", Instant.EPOCH));
    assertThat(result.term()).isEqualTo("尊嘟假嘟");
    assertThat(result.sourceSummary()).isEqualTo("页面摘要");
    assertThat(result.sourceBody()).contains("正文 第一段。", "正文第二段。").doesNotContain("bad()");
    assertThat(result.sourceCategory()).isEqualTo("网络流行语");
    assertThat(result.sourcePublishedAt()).isEqualTo(Instant.parse("2023-08-01T10:00:00Z"));
  }
}
