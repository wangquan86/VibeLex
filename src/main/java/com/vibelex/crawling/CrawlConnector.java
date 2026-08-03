package com.vibelex.crawling;

import java.time.Instant;
import java.util.List;
import tools.jackson.databind.JsonNode;

public interface CrawlConnector {
  String sourceCode();

  String sourceName();

  EnumerationResult enumerate(JsonNode checkpoint);

  FetchedCrawlEntry fetch(CrawlPointer pointer);

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

  record OriginReference(String title, String url) {}

  record FetchedCrawlEntry(
      String term,
      String sourceSummary,
      String sourceBody,
      List<String> sourceExamples,
      String sourceCategory,
      List<String> sourceTags,
      String sourceUrl,
      String sourceRecordKey,
      Instant sourcePublishedAt,
      String parserVersion) {
    public FetchedCrawlEntry {
      sourceExamples = sourceExamples == null ? List.of() : List.copyOf(sourceExamples);
      sourceTags = sourceTags == null ? List.of() : List.copyOf(sourceTags);
    }
  }

  record CrawledEntry(
      String term,
      String definition,
      List<String> examples,
      String category,
      String sourceCategory,
      List<String> sourceTags,
      String sourceUrl,
      String sourceRecordKey,
      String parserVersion,
      String origin,
      List<OriginReference> originReferences,
      boolean needsReview,
      List<String> issues,
      String aiProvider,
      String aiModel,
      String processorVersion,
      java.math.BigDecimal confidence) {
    public CrawledEntry {
      examples = examples == null ? List.of() : List.copyOf(examples);
      category = category == null || category.isBlank() ? "other" : category;
      sourceTags = sourceTags == null ? List.of() : List.copyOf(sourceTags);
      originReferences = originReferences == null ? List.of() : List.copyOf(originReferences);
      issues = issues == null ? List.of() : List.copyOf(issues);
    }

    public CrawledEntry(
        String term,
        String definition,
        List<String> examples,
        String category,
        String sourceCategory,
        List<String> sourceTags,
        String sourceUrl,
        String sourceRecordKey,
        String parserVersion) {
      this(
          term,
          definition,
          examples,
          category,
          sourceCategory,
          sourceTags,
          sourceUrl,
          sourceRecordKey,
          parserVersion,
          null,
          List.of(),
          false,
          List.of(),
          null,
          null,
          null,
          null);
    }
  }
}
