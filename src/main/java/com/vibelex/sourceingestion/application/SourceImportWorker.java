package com.vibelex.sourceingestion.application;

import com.vibelex.shared.persistence.MyBatisDatabase;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Processes file-import records one at a time, using the same lease model as crawler records. */
@Service
public class SourceImportWorker {
  private static final Logger log = LoggerFactory.getLogger(SourceImportWorker.class);
  private final MyBatisDatabase database;
  private final ObjectMapper mapper;
  private final TransactionTemplate transactions;
  private final Map<String, ImportRecordEnricher> enrichers;
  private final long leaseSeconds;
  private final int maximumAttempts;
  private final String actorId;
  private final String workerId = "import-" + UUID.randomUUID();

  public SourceImportWorker(
      MyBatisDatabase database,
      ObjectMapper mapper,
      TransactionTemplate transactions,
      List<ImportRecordEnricher> enrichers,
      @Value("${vibelex.import.worker.lease-seconds:120}") long leaseSeconds,
      @Value("${vibelex.import.worker.maximum-attempts:3}") int maximumAttempts,
      @Value("${vibelex.import.worker.actor-id:system}") String actorId) {
    this.database = database;
    this.mapper = mapper;
    this.transactions = transactions;
    Map<String, ImportRecordEnricher> registry = new LinkedHashMap<>();
    for (ImportRecordEnricher enricher : enrichers)
      registry.put(enricherSourceCode(enricher), enricher);
    this.enrichers = Map.copyOf(registry);
    this.leaseSeconds = Math.max(10, leaseSeconds);
    this.maximumAttempts = Math.max(1, maximumAttempts);
    this.actorId = actorId == null || actorId.isBlank() ? "system" : actorId.trim();
  }

  @Scheduled(fixedDelayString = "${vibelex.import.worker.fixed-delay-millis:500}")
  public void process() {
    recoverStaleRecords();
    recoverStalePlanningRuns();
    Map<String, Object> record = claim();
    if (record == null) {
      finishRuns();
      return;
    }
    processRecord(record);
    finishRuns();
  }

  private Map<String, Object> claim() {
    return transactions.execute(
        ignored -> {
          Map<String, Object> record =
              database.optionalOne(
                  """
                  SELECT r.*, run.source_code, run.source_name, run.import_fingerprint
                  FROM source_import_records r
                  JOIN source_import_runs run ON run.id=r.import_run_id
                  WHERE run.status='running'
                    AND r.status='pending'
                    AND (r.next_attempt_at IS NULL OR r.next_attempt_at <= NOW(3))
                  ORDER BY r.import_run_id, r.source_index, r.id
                  LIMIT 1 FOR UPDATE SKIP LOCKED
                  """);
          if (record == null) return null;
          int changed =
              database.update(
                  """
                  UPDATE source_import_records
                  SET status='processing', attempt_count=attempt_count+1,
                      lease_owner=?, lease_until=DATE_ADD(NOW(3), INTERVAL ? SECOND),
                      processor_stage='deduplicate'
                  WHERE id=? AND status='pending'
                  """,
                  workerId,
                  leaseSeconds,
                  number(record.get("id")));
          if (changed != 1) return null;
          database.update(
              "UPDATE source_import_runs SET updated_at=NOW(3) WHERE id=?",
              number(record.get("import_run_id")));
          return record;
        });
  }

  void processRecord(Map<String, Object> record) {
    long id = number(record.get("id"));
    long runId = number(record.get("import_run_id"));
    String sourceCode = String.valueOf(record.get("source_code"));
    try {
      String normalized = String.valueOf(record.get("normalized_term"));
      DuplicateTarget duplicate = findDuplicate(normalized);
      if (duplicate != null) {
        markDuplicate(id, runId, normalized, duplicate);
        return;
      }
      ImportedCandidateData source = source(record);
      ImportRecordEnricher.EnrichedRecord enriched;
      try {
        enriched = enrich(record, id, sourceCode, source);
      } catch (AiEnrichmentException failure) {
        int attempt = ((Number) record.get("attempt_count")).intValue() + 1;
        if (!"chime".equals(sourceCode) || attempt < maximumAttempts) throw failure.cause();
        log.warn(
            "CHIME AI 丰富化连续失败，使用来源基础内容创建候选 runId={} recordId={} attempts={}",
            runId,
            id,
            attempt,
            failure.cause());
        enriched =
            new ImportRecordEnricher.EnrichedRecord(
                source.definition(), source.note(), null, null, null, null, null);
      }
      if (enriched.ignored()) {
        markIgnored(id, runId, enriched);
        return;
      }
      markStage(id, "candidate_creation");
      createCandidate(record, source, enriched);
    } catch (RuntimeException e) {
      fail(record, e);
      log.warn("文件导入词条处理失败 runId={} recordId={}", runId, id, e);
    }
  }

  private ImportedCandidateData source(Map<String, Object> record) {
    return new ImportedCandidateData(
        String.valueOf(record.get("term_raw")),
        record.get("definition_raw") == null ? "" : String.valueOf(record.get("definition_raw")),
        record.get("source_url") == null ? null : String.valueOf(record.get("source_url")),
        record.get("parser_version") == null ? "" : String.valueOf(record.get("parser_version")),
        parseNote(record.get("processing_note")));
  }

  private ImportRecordEnricher.EnrichedRecord enrich(
      Map<String, Object> record, long recordId, String sourceCode, ImportedCandidateData source) {
    ImportRecordEnricher enricher = enrichers.get(sourceCode);
    if (enricher == null)
      return new ImportRecordEnricher.EnrichedRecord(
          source.definition(), source.note(), null, null, null, null, null);
    markStage(recordId, "ai_enrichment");
    Object cached = record.get("ai_output");
    ImportRecordEnricher.EnrichedRecord result;
    try {
      result =
          cached != null && !String.valueOf(cached).isBlank()
              ? enricher.restore(
                  source.term(),
                  source.definition(),
                  source.note(),
                  String.valueOf(cached),
                  String.valueOf(record.get("ai_provider")),
                  String.valueOf(record.get("ai_model")))
              : enricher.enrich(source.term(), source.definition(), source.note());
    } catch (RuntimeException failure) {
      throw new AiEnrichmentException(failure);
    }
    database.update(
        """
        UPDATE source_import_records
        SET processing_note=?, processor_version=?, ai_provider=?, ai_model=?, ai_output=?
        WHERE id=? AND status='processing'
        """,
        candidateNote(result.processingNote()),
        result.processorVersion(),
        result.provider(),
        result.model(),
        result.aiOutput(),
        recordId);
    return result;
  }

  private void createCandidate(
      Map<String, Object> record,
      ImportedCandidateData source,
      ImportRecordEnricher.EnrichedRecord enriched) {
    long runId = number(record.get("import_run_id"));
    String fingerprint = String.valueOf(record.get("import_fingerprint"));
    String normalized = String.valueOf(record.get("normalized_term"));
    String note = candidateNote(enriched.processingNote());
    transactions.executeWithoutResult(
        ignored -> {
          lockCandidateAdmission(normalized);
          DuplicateTarget duplicate = findDuplicate(normalized);
          if (duplicate != null) {
            updateDuplicateRecord(number(record.get("id")), normalized, duplicate);
            refreshRunCounters(runId);
            return;
          }
          long candidateId =
              database.insert(
                  """
                  INSERT INTO candidate_entries(
                      import_run_id, import_fingerprint, source_record_key,
                      term_raw, normalized_term, definition_raw, source_url,
                      parser_version, source_type, created_by, status,
                      duplicate_meme_id, processing_note
                  ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'import', ?, 'editing', NULL, ?)
                  """,
                  runId,
                  fingerprint,
                  String.valueOf(record.get("source_record_key")),
                  source.term(),
                  normalized,
                  enriched.definition(),
                  source.sourceUrl(),
                  source.parserVersion(),
                  actorId,
                  note);
          int changed =
              database.update(
                  """
                  UPDATE source_import_records
                  SET status='imported', candidate_id=?, processing_note=?, processor_stage='candidate_creation',
                      processor_version=?, ai_provider=?, ai_model=?, ai_output=?,
                      processed_at=NOW(3), lease_owner=NULL, lease_until=NULL,
                      error_type=NULL, error_message=NULL
                  WHERE id=? AND status='processing'
                  """,
                  candidateId,
                  note,
                  enriched.processorVersion(),
                  enriched.provider(),
                  enriched.model(),
                  enriched.aiOutput(),
                  number(record.get("id")));
          if (changed != 1) throw new IllegalStateException("导入记录状态已变化，无法关联候选");
          refreshRunCounters(runId);
        });
  }

  private void markDuplicate(long id, long runId, String normalized, DuplicateTarget target) {
    transactions.executeWithoutResult(
        ignored -> {
          updateDuplicateRecord(id, normalized, target);
          refreshRunCounters(runId);
        });
  }

  private void updateDuplicateRecord(long id, String normalized, DuplicateTarget target) {
    int changed =
        database.update(
            """
            UPDATE source_import_records
            SET status='duplicate', normalized_term=?, duplicate_target_type=?, duplicate_target_id=?,
                processed_at=NOW(3), lease_owner=NULL, lease_until=NULL,
                error_type=NULL, error_message=NULL
            WHERE id=? AND status='processing'
            """,
            normalized,
            target == null ? "candidate" : target.type(),
            target == null ? null : target.id(),
            id);
    if (changed != 1) throw new IllegalStateException("导入记录状态已变化，无法标记重复");
  }

  private void markIgnored(long id, long runId, ImportRecordEnricher.EnrichedRecord result) {
    transactions.executeWithoutResult(
        ignored -> {
          database.update(
              """
              UPDATE source_import_records
              SET status='ignored', processor_stage='ai_enrichment', processor_version=?,
                  ai_provider=?, ai_model=?, ai_output=?, processed_at=NOW(3),
                  lease_owner=NULL, lease_until=NULL, error_type='ContentRejected', error_message=?
              WHERE id=? AND status='processing'
              """,
              result.processorVersion(),
              result.provider(),
              result.model(),
              result.aiOutput(),
              result.ignoredReason(),
              id);
          refreshRunCounters(runId);
        });
  }

  private void markStage(long id, String stage) {
    database.update(
        "UPDATE source_import_records SET processor_stage=? WHERE id=? AND status='processing'",
        stage,
        id);
  }

  private void fail(Map<String, Object> record, RuntimeException error) {
    long id = number(record.get("id"));
    long runId = number(record.get("import_run_id"));
    int attempt = ((Number) record.get("attempt_count")).intValue() + 1;
    String message = safeError(error);
    if (attempt >= maximumAttempts) {
      transactions.executeWithoutResult(
          ignored -> {
            database.update(
                """
                 UPDATE source_import_records
                 SET status='failed', lease_owner=NULL, lease_until=NULL,
                     error_type=?, error_message=?, processed_at=NOW(3)
                 WHERE id=? AND status='processing'
                """,
                error.getClass().getSimpleName(),
                message,
                id);
            refreshRunCounters(runId);
          });
    } else {
      int delay = attempt == 1 ? 30 : attempt == 2 ? 120 : 600;
      database.update(
          """
          UPDATE source_import_records
          SET status='pending', lease_owner=NULL, lease_until=NULL,
              next_attempt_at=DATE_ADD(NOW(3), INTERVAL ? SECOND),
              error_type=?, error_message=? WHERE id=? AND status='processing'
          """,
          delay,
          error.getClass().getSimpleName(),
          message,
          id);
    }
  }

  private void finishRuns() {
    List<Map<String, Object>> runs =
        database.list(
            "SELECT id FROM source_import_runs WHERE status='running' ORDER BY id LIMIT 20");
    for (Map<String, Object> run : runs) {
      long runId = number(run.get("id"));
      refreshRunCounters(runId);
      Number active =
          (Number)
              database.scalar(
                  "SELECT COUNT(*) FROM source_import_records WHERE import_run_id=? AND status IN ('pending','processing')",
                  runId);
      if (active != null && active.longValue() > 0) continue;
      Number failed =
          (Number)
              database.scalar(
                  "SELECT COUNT(*) FROM source_import_records WHERE import_run_id=? AND status='failed'",
                  runId);
      Number completed =
          (Number)
              database.scalar(
                  "SELECT COUNT(*) FROM source_import_records WHERE import_run_id=? AND status IN ('imported','duplicate','ignored')",
                  runId);
      String status =
          failed != null && failed.longValue() > 0
              ? completed != null && completed.longValue() > 0 ? "partial_success" : "failed"
              : "succeeded";
      database.update(
          """
          UPDATE source_import_runs
          SET status=?, finished_at=?
          WHERE id=? AND status='running'
            AND NOT EXISTS (
              SELECT 1 FROM source_import_records
              WHERE import_run_id=? AND status IN ('pending','processing')
            )
          """,
          status,
          LocalDateTime.now(),
          runId,
          runId);
    }
  }

  private void recoverStaleRecords() {
    database.update(
        """
        UPDATE source_import_records
        SET status='pending', lease_owner=NULL, lease_until=NULL
        WHERE status='processing' AND lease_until < NOW(3)
        """);
  }

  private void recoverStalePlanningRuns() {
    database.update(
        """
        UPDATE source_import_runs
        SET status='failed', failed_count=GREATEST(failed_count, 1),
            error_summary='导入任务在记录规划阶段中断', finished_at=NOW(3)
        WHERE status='planning' AND started_at < DATE_SUB(NOW(3), INTERVAL 10 MINUTE)
        """);
  }

  private void refreshRunCounters(long runId) {
    database.update(
        """
        UPDATE source_import_runs
        SET imported_count=(SELECT COUNT(*) FROM source_import_records WHERE import_run_id=? AND status='imported'),
            candidate_count=(SELECT COUNT(*) FROM source_import_records WHERE import_run_id=? AND status='imported'),
            duplicate_count=(SELECT COUNT(*) FROM source_import_records WHERE import_run_id=? AND status='duplicate'),
            ignored_count=(SELECT COUNT(*) FROM source_import_records WHERE import_run_id=? AND status='ignored'),
            failed_count=(SELECT COUNT(*) FROM source_import_records WHERE import_run_id=? AND status='failed')
        WHERE id=?
        """,
        runId,
        runId,
        runId,
        runId,
        runId,
        runId);
  }

  private DuplicateTarget findDuplicate(String normalized) {
    Map<String, Object> duplicate =
        database.optionalOne(
            """
            SELECT target_type, target_id
            FROM (
                SELECT 'meme' AS target_type, id AS target_id, 1 AS match_priority
                FROM meme_entries
                WHERE normalized_term=? AND language_code='zh-CN' AND status='published'
                UNION ALL
                SELECT 'variant' AS target_type, v.meme_id AS target_id, 2 AS match_priority
                FROM meme_variants v
                JOIN meme_entries e ON e.id=v.meme_id
                WHERE v.normalized_variant=? AND v.status='active' AND e.status='published'
                UNION ALL
                SELECT 'candidate' AS target_type, id AS target_id, 3 AS match_priority
                FROM candidate_entries
                WHERE normalized_term=? AND status IN ('editing', 'pending_review', 'returned')
            ) duplicate_matches
            ORDER BY match_priority
            LIMIT 1
            """,
            normalized,
            normalized,
            normalized);
    return duplicate == null
        ? null
        : new DuplicateTarget(
            String.valueOf(duplicate.get("target_type")), number(duplicate.get("target_id")));
  }

  private void lockCandidateAdmission(String normalized) {
    database.update(
        "INSERT IGNORE INTO candidate_admission_locks(normalized_term) VALUES (?)", normalized);
    database.optionalOne(
        "SELECT normalized_term FROM candidate_admission_locks WHERE normalized_term=? FOR UPDATE",
        normalized);
  }

  private Map<String, Object> parseNote(Object value) {
    if (value == null || String.valueOf(value).isBlank()) return new LinkedHashMap<>();
    try {
      JsonNode node = mapper.readTree(String.valueOf(value));
      return mapper.convertValue(node, Map.class);
    } catch (Exception e) {
      throw new IllegalStateException("来源处理说明不是有效 JSON", e);
    }
  }

  private String candidateNote(Map<String, Object> note) {
    try {
      Map<String, Object> saved = new LinkedHashMap<>(note == null ? Map.of() : note);
      saved.putIfAbsent("category", "other");
      saved.putIfAbsent("examples", List.of());
      saved.putIfAbsent("profanity", false);
      saved.putIfAbsent("offense", false);
      saved.putIfAbsent("variants", List.of());
      return mapper.writeValueAsString(saved);
    } catch (Exception e) {
      throw new IllegalStateException("无法保存来源处理说明", e);
    }
  }

  private String enricherSourceCode(ImportRecordEnricher enricher) {
    if (enricher.supports("buzzword")) return "buzzword";
    if (enricher.supports("chime")) return "chime";
    throw new IllegalStateException("无法识别导入丰富化处理器: " + enricher.getClass().getName());
  }

  private long number(Object value) {
    return ((Number) value).longValue();
  }

  private String safeError(Exception error) {
    String value =
        error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    return value.length() > 2000 ? value.substring(0, 2000) : value;
  }

  private record ImportedCandidateData(
      String term,
      String definition,
      String sourceUrl,
      String parserVersion,
      Map<String, Object> note) {}

  private record DuplicateTarget(String type, Long id) {}

  private static final class AiEnrichmentException extends RuntimeException {
    private AiEnrichmentException(RuntimeException cause) {
      super(cause);
    }

    private RuntimeException cause() {
      return (RuntimeException) getCause();
    }
  }
}
