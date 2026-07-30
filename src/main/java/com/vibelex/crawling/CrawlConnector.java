package com.vibelex.crawling;

import java.time.Instant;
import java.util.List;
import tools.jackson.databind.JsonNode;

public interface CrawlConnector {
  String sourceCode();

  String sourceName();

  EnumerationResult enumerate(JsonNode checkpoint);

  CrawledEntry fetch(CrawlPointer pointer);

  default int maximumAttempts() {
    return 3;
  }

  class IgnoredPageException extends RuntimeException {
    public IgnoredPageException(String message) {
      super(message);
    }
  }

  record CrawlPointer(String sourceRecordKey, String sourceUrl, Instant sourceModifiedAt) {}

  record EnumerationResult(List<CrawlPointer> items, JsonNode nextCheckpoint) {}

  record CrawledEntry(
      String term,
      String definition,
      List<String> examples,
      String category,
      String sourceCategory,
      List<String> sourceTags,
      String sourceUrl,
      String sourceRecordKey,
      String parserVersion) {
    public CrawledEntry {
      examples = examples == null ? List.of() : List.copyOf(examples);
      category = category == null || category.isBlank() ? "other" : category;
      sourceTags = sourceTags == null ? List.of() : List.copyOf(sourceTags);
    }
  }
}
