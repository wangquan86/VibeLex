package com.vibelex.crawling;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class CrawlScheduler {
  private static final Logger log = LoggerFactory.getLogger(CrawlScheduler.class);
  private final CrawlProperties properties;
  private final CrawlExecutionService executions;

  public CrawlScheduler(CrawlProperties properties, CrawlExecutionService executions) {
    this.properties = properties;
    this.executions = executions;
  }

  @Scheduled(cron = "${vibelex.crawling.popcidian.sync-cron:0 30 3 * * *}")
  public void schedulePopCidianSync() {
    if (!properties.isEnabled() || !properties.getPopcidian().isEnabled()) return;
    try {
      executions.startSync(PopCidianConnector.SOURCE_CODE);
    } catch (IllegalStateException e) {
      log.debug("跳过波普词典同步任务: {}", e.getMessage());
    } catch (RuntimeException e) {
      log.warn("创建波普词典同步任务失败", e);
    }
  }
}
