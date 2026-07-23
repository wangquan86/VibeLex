package com.vibelex.sourceingestion.application;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Source-specific parsing only. Persistence and import bookkeeping belong to SourceImportService.
 */
public interface CandidateImporter {
  String sourceCode();

  String sourceName();

  String sourceUrl();

  String parserVersion();

  /**
   * Identifies files owned by this source. This must be a cheap filename-only check; structural
   * validation belongs in {@link #parse(Path)}.
   */
  boolean supportsFileName(String fileName);

  ImportedBatch parse(Path file);

  record ImportedBatch(
      int totalCount, int rejectedCount, List<ImportedCandidate> candidates, List<String> errors) {}

  record ImportedCandidate(
      int sourceIndex,
      String sourceRecordKey,
      String term,
      String definition,
      String sourceUrl,
      Map<String, Object> processingNote) {}
}
