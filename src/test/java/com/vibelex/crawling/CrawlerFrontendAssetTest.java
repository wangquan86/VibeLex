package com.vibelex.crawling;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class CrawlerFrontendAssetTest {

  @Test
  void startsPollingBeforeWaitingForTheSynchronousPlanningRequest() throws IOException {
    String javascript =
        new ClassPathResource("static/assets/js/app.js").getContentAsString(StandardCharsets.UTF_8);
    String action =
        javascript.substring(
            javascript.indexOf("async function crawlAction"),
            javascript.indexOf("async function loadPage"));

    assertThat(action.indexOf("crawlStartPending = true"))
        .isGreaterThanOrEqualTo(0)
        .isLessThan(action.indexOf("await api("));
    assertThat(action.indexOf("startCrawlerPolling();"))
        .isGreaterThanOrEqualTo(0)
        .isLessThan(action.indexOf("await api("));
  }
}
