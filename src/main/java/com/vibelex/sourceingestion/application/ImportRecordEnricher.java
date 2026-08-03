package com.vibelex.sourceingestion.application;

import java.util.Map;

/** Optional source-specific content enrichment performed before a candidate is created. */
public interface ImportRecordEnricher {
  boolean supports(String sourceCode);

  EnrichedRecord enrich(String term, String definition, Map<String, Object> processingNote);

  default EnrichedRecord restore(
      String term,
      String definition,
      Map<String, Object> processingNote,
      String aiOutput,
      String provider,
      String model) {
    throw new IllegalStateException("该丰富化处理器不支持复用 AI 输出");
  }

  record EnrichedRecord(
      String definition,
      Map<String, Object> processingNote,
      String provider,
      String model,
      String processorVersion,
      String aiOutput,
      String ignoredReason) {
    public boolean ignored() {
      return ignoredReason != null && !ignoredReason.isBlank();
    }
  }
}
