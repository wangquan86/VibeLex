package com.vibelex.sourceingestion.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vibelex.shared.persistence.MyBatisDatabase;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

class SourceImportWorkerTest {

  @Test
  void duplicateChimeRecordSkipsAiEnrichment() {
    MyBatisDatabase database = mock(MyBatisDatabase.class);
    ImportRecordEnricher enricher = mock(ImportRecordEnricher.class);
    when(enricher.supports("buzzword")).thenReturn(false);
    when(enricher.supports("chime")).thenReturn(true);
    when(database.optionalOne(argThat(sql -> sql.contains("UNION ALL")), any(Object[].class)))
        .thenReturn(Map.of("target_type", "meme", "target_id", 7L));
    when(database.update(
            argThat(sql -> sql.contains("SET status='duplicate'")), any(Object[].class)))
        .thenReturn(1);
    SourceImportWorker worker =
        new SourceImportWorker(
            database,
            new ObjectMapper(),
            executingTransactions(),
            List.of(enricher),
            120,
            3,
            "system");

    worker.processRecord(record("chime", 0));

    verify(enricher, never()).enrich(anyString(), anyString(), any());
    verify(database).optionalOne(argThat(sql -> sql.contains("UNION ALL")), any(Object[].class));
    verify(database)
        .update(argThat(sql -> sql.contains("SET status='duplicate'")), any(Object[].class));
  }

  @Test
  void createsChimeCandidateAfterThirdAiEnrichmentFailure() {
    MyBatisDatabase database = mock(MyBatisDatabase.class);
    TransactionTemplate transactions = executingTransactions();
    ImportRecordEnricher enricher = mock(ImportRecordEnricher.class);
    when(enricher.supports("buzzword")).thenReturn(false);
    when(enricher.supports("chime")).thenReturn(true);
    when(enricher.enrich(anyString(), anyString(), any()))
        .thenThrow(new IllegalStateException("AI unavailable"));
    when(database.optionalOne(argThat(sql -> sql.contains("UNION ALL")), any(Object[].class)))
        .thenReturn(null);
    when(database.insert(anyString(), any(Object[].class))).thenReturn(42L);
    when(database.update(
            argThat(sql -> sql.contains("SET status='imported'")), any(Object[].class)))
        .thenReturn(1);
    SourceImportWorker worker =
        new SourceImportWorker(
            database, new ObjectMapper(), transactions, List.of(enricher), 120, 3, "system");

    worker.processRecord(record("chime", 2));

    verify(database)
        .insert(argThat(sql -> sql.contains("INSERT INTO candidate_entries")), any(Object[].class));
    verify(database)
        .update(
            argThat(sql -> sql.contains("INSERT IGNORE INTO candidate_admission_locks")),
            any(Object[].class));
    verify(database).optionalOne(argThat(sql -> sql.contains("FOR UPDATE")), any(Object[].class));
    verify(database)
        .update(argThat(sql -> sql.contains("SET status='imported'")), any(Object[].class));
    verify(database, never())
        .update(argThat(sql -> sql.contains("SET status='failed'")), any(Object[].class));
  }

  @Test
  void retriesChimeBeforeTheThirdAiEnrichmentFailure() {
    MyBatisDatabase database = mock(MyBatisDatabase.class);
    ImportRecordEnricher enricher = mock(ImportRecordEnricher.class);
    when(enricher.supports("buzzword")).thenReturn(false);
    when(enricher.supports("chime")).thenReturn(true);
    when(enricher.enrich(anyString(), anyString(), any()))
        .thenThrow(new IllegalStateException("AI unavailable"));
    when(database.optionalOne(argThat(sql -> sql.contains("UNION ALL")), any(Object[].class)))
        .thenReturn(null);
    SourceImportWorker worker =
        new SourceImportWorker(
            database,
            new ObjectMapper(),
            executingTransactions(),
            List.of(enricher),
            120,
            3,
            "system");

    worker.processRecord(record("chime", 0));

    verify(database, never()).insert(anyString(), any(Object[].class));
    verify(database)
        .update(argThat(sql -> sql.contains("SET status='pending'")), any(Object[].class));
  }

  @Test
  void keepsBuzzwordFailedAfterThirdAiEnrichmentFailure() {
    MyBatisDatabase database = mock(MyBatisDatabase.class);
    TransactionTemplate transactions = executingTransactions();
    ImportRecordEnricher enricher = mock(ImportRecordEnricher.class);
    when(enricher.supports("buzzword")).thenReturn(true);
    when(enricher.enrich(anyString(), anyString(), any()))
        .thenThrow(new IllegalStateException("AI unavailable"));
    when(database.optionalOne(argThat(sql -> sql.contains("UNION ALL")), any(Object[].class)))
        .thenReturn(null);
    SourceImportWorker worker =
        new SourceImportWorker(
            database, new ObjectMapper(), transactions, List.of(enricher), 120, 3, "system");

    worker.processRecord(record("buzzword", 2));

    verify(database, never()).insert(anyString(), any(Object[].class));
    verify(database)
        .update(argThat(sql -> sql.contains("SET status='failed'")), any(Object[].class));
  }

  private Map<String, Object> record(String sourceCode, int attempts) {
    return Map.ofEntries(
        Map.entry("id", 1L),
        Map.entry("import_run_id", 9L),
        Map.entry("source_code", sourceCode),
        Map.entry("normalized_term", "测试词"),
        Map.entry("term_raw", "测试词"),
        Map.entry("definition_raw", "来源原始释义"),
        Map.entry("source_url", "https://example.test/term"),
        Map.entry("parser_version", "v1"),
        Map.entry("processing_note", "{}"),
        Map.entry("import_fingerprint", "fingerprint"),
        Map.entry("source_record_key", "source-key"),
        Map.entry("attempt_count", attempts));
  }

  @SuppressWarnings("unchecked")
  private TransactionTemplate executingTransactions() {
    TransactionTemplate transactions = mock(TransactionTemplate.class);
    doAnswer(
            invocation -> {
              Consumer<TransactionStatus> action = invocation.getArgument(0);
              action.accept(mock(TransactionStatus.class));
              return null;
            })
        .when(transactions)
        .executeWithoutResult(any());
    return transactions;
  }
}
