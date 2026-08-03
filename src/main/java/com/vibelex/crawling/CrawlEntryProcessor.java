package com.vibelex.crawling;

import com.vibelex.crawling.CrawlConnector.CrawledEntry;
import com.vibelex.crawling.CrawlConnector.FetchedCrawlEntry;

public interface CrawlEntryProcessor {
  boolean supports(String sourceCode);

  ProcessedEntry process(FetchedCrawlEntry source);

  default String processorVersion() {
    return null;
  }

  default ProcessedEntry restore(FetchedCrawlEntry source, String aiOutput, String aiModel) {
    throw new IllegalStateException("该内容处理器不支持复用 AI 输出");
  }

  record ProcessedEntry(
      CrawledEntry entry,
      String processorVersion,
      String aiModel,
      String aiOutput,
      String ignoredReason) {
    public static ProcessedEntry imported(
        CrawledEntry entry, String processorVersion, String aiModel, String aiOutput) {
      return new ProcessedEntry(entry, processorVersion, aiModel, aiOutput, null);
    }

    public static ProcessedEntry ignored(
        String processorVersion, String aiModel, String aiOutput, String reason) {
      return new ProcessedEntry(null, processorVersion, aiModel, aiOutput, reason);
    }
  }
}
