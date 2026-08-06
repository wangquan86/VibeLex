package com.vibelex.crawling;

import com.vibelex.candidatediscovery.application.CandidateService;
import com.vibelex.candidatediscovery.application.CandidateService.CrawlerImportResult;
import com.vibelex.crawling.CrawlConnector.CrawlPointer;
import com.vibelex.crawling.CrawlConnector.CrawledEntry;
import com.vibelex.crawling.CrawlConnector.FetchedCrawlEntry;
import com.vibelex.crawling.CrawlEntryProcessor.ProcessedEntry;
import com.vibelex.shared.persistence.MyBatisDatabase;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Service
public class CrawlWorker {
  private static final Logger log = LoggerFactory.getLogger(CrawlWorker.class);
  private final MyBatisDatabase database;
  private final CrawlProperties properties;
  private final CrawlExecutionService executions;
  private final CandidateService candidates;
  private final TransactionTemplate transactions;
  private final ObjectMapper mapper;
  private final Map<String, CrawlEntryProcessor> processors;
  private final String workerId = "crawler-" + UUID.randomUUID();

  public CrawlWorker(
      MyBatisDatabase database,
      CrawlProperties properties,
      CrawlExecutionService executions,
      CandidateService candidates,
      TransactionTemplate transactions,
      ObjectMapper mapper,
      List<CrawlEntryProcessor> processors) {
    this.database = database;
    this.properties = properties;
    this.executions = executions;
    this.candidates = candidates;
    this.transactions = transactions;
    this.mapper = mapper;
    this.processors =
        processors.stream()
            .collect(Collectors.toUnmodifiableMap(this::sourceCode, Function.identity()));
  }

  @Scheduled(fixedDelayString = "${vibelex.crawling.worker.fixed-delay-millis:500}")
  public void processPopCidian() {
    processSource(PopCidianConnector.SOURCE_CODE);
  }

  @Scheduled(fixedDelayString = "${vibelex.crawling.worker.fixed-delay-millis:500}")
  public void processRegengBaike() {
    processSource(RegengBaikeConnector.SOURCE_CODE);
  }

  private void processSource(String sourceCode) {
    if (!properties.isEnabled()) return;
    recoverStaleRecords();
    executions.recoverStalePlanning();
    Map<String, Object> record = claim(sourceCode);
    if (record == null) return;
    processRecord(record);
  }

  private Map<String, Object> claim(String sourceCode) {
    return transactions.execute(
        ignored -> {
          Map<String, Object> record =
              database.optionalOne(
                  """
                  SELECT r.* FROM crawl_records r
                  JOIN crawl_checkpoints c ON c.source_code=r.source_code
                  WHERE c.current_status='running'
                    AND r.source_code=?
                    AND r.batch_token=c.active_batch_token
                    AND r.status IN ('pending', 'retry_wait')
                    AND (r.next_attempt_at IS NULL OR r.next_attempt_at <= NOW(3))
                  ORDER BY r.id
                  LIMIT 1 FOR UPDATE SKIP LOCKED
                  """,
                  sourceCode);
          if (record == null) return null;
          long id = number(record.get("id"));
          int changed =
              database.update(
                  """
                  UPDATE crawl_records
                  SET status='processing', attempt_count=attempt_count+1,
                      lease_owner=?, lease_until=DATE_ADD(NOW(3), INTERVAL ? SECOND)
                  WHERE id=? AND status IN ('pending', 'retry_wait')
                  """,
                  workerId,
                  properties.getWorker().getLeaseSeconds(),
                  id);
          return changed == 1 ? record : null;
        });
  }

  void processRecord(Map<String, Object> record) {
    long id = number(record.get("id"));
    String sourceCode = String.valueOf(record.get("source_code"));
    CrawlConnector connector = executions.connectorForWorker(sourceCode);
    try {
      FetchedCrawlEntry fetched = fetched(record, connector);
      if (!executions.isRunning(sourceCode)) {
        resetPending(id);
        return;
      }
      CrawlerImportResult duplicate = candidates.precheckCrawlerDuplicate(fetched.term());
      if (duplicate != null) {
        markDuplicate(id, sourceCode, duplicate);
        return;
      }
      ProcessedEntry processed;
      RuntimeException fallbackFailure = null;
      try {
        processed = processed(record, sourceCode, fetched);
      } catch (AiProcessingException failure) {
        int attempt = ((Number) record.get("attempt_count")).intValue() + 1;
        if (!PopCidianConnector.SOURCE_CODE.equals(sourceCode)
            || attempt < connector.maximumAttempts()) throw failure.cause();
        log.warn(
            "波普词典 AI 丰富化连续失败，使用来源基础内容创建候选 source={} recordId={} attempts={}",
            sourceCode,
            id,
            attempt,
            failure.cause());
        fallbackFailure = failure.cause();
        processed = popCidianFallback(fetched);
      }
      if (!executions.isRunning(sourceCode)) {
        resetPending(id);
        return;
      }
      if (processed.ignoredReason() != null) {
        markIgnored(id, sourceCode, processed.ignoredReason(), processed);
        return;
      }
      CrawledEntry entry = processed.entry();
      String fallbackSummary =
          fallbackFailure == null ? null : "AI丰富连续3次失败，已使用来源原始内容进入候选：" + safeError(fallbackFailure);
      transactions.executeWithoutResult(
          ignored -> {
            CrawlerImportResult result =
                candidates.createFromCrawler(
                    sourceCode, connector.sourceName(), entry, properties.getWorker().getActorId());
            String completionErrorType =
                fallbackSummary != null && "imported".equals(result.status())
                    ? "AiEnrichmentFallback"
                    : null;
            String completionErrorMessage = completionErrorType == null ? null : fallbackSummary;
            database.update(
                """
                UPDATE crawl_records
                SET status=?, normalized_term=?, candidate_id=?,
                    duplicate_target_type=?, duplicate_target_id=?,
                    processed_at=NOW(3), lease_owner=NULL, lease_until=NULL,
                    error_type=?, error_message=?
                WHERE id=? AND status='processing'
                """,
                result.status(),
                result.normalizedTerm(),
                result.candidateId(),
                result.duplicateTargetType(),
                result.duplicateTargetId(),
                completionErrorType,
                completionErrorMessage,
                id);
            String counter =
                "imported".equals(result.status()) ? "imported_count" : "duplicate_count";
            database.update(
                "UPDATE crawl_checkpoints SET "
                    + counter
                    + "="
                    + counter
                    + "+1 WHERE source_code=?",
                sourceCode);
          });
    } catch (CrawlConnector.IgnoredPageException e) {
      database.update(
          """
          UPDATE crawl_records SET status='ignored', processed_at=NOW(3),
              lease_owner=NULL, lease_until=NULL, error_type=NULL, error_message=?
          WHERE id=?
          """,
          safeError(e),
          id);
      database.update(
          "UPDATE crawl_checkpoints SET ignored_count=ignored_count+1 WHERE source_code=?",
          sourceCode);
    } catch (RuntimeException e) {
      fail(record, connector, e);
      log.warn("爬取记录处理失败 source={} recordId={}", sourceCode, id, e);
    } finally {
      executions.finishIfComplete(sourceCode);
    }
  }

  private FetchedCrawlEntry fetched(Map<String, Object> record, CrawlConnector connector) {
    Object payload = record.get("source_payload");
    if (payload != null && !String.valueOf(payload).isBlank()) {
      try {
        return mapper.readValue(String.valueOf(payload), FetchedCrawlEntry.class);
      } catch (Exception e) {
        throw new IllegalStateException("已保存的原始材料无效", e);
      }
    }
    FetchedCrawlEntry fetched = connector.fetch(pointer(record));
    try {
      database.update(
          "UPDATE crawl_records SET source_payload=?, fetched_at=NOW(3) WHERE id=? AND status='processing'",
          mapper.writeValueAsString(fetched),
          number(record.get("id")));
    } catch (Exception e) {
      throw new IllegalStateException("无法保存原始材料", e);
    }
    return fetched;
  }

  private ProcessedEntry processed(
      Map<String, Object> record, String sourceCode, FetchedCrawlEntry fetched) {
    CrawlEntryProcessor processor = processors.get(sourceCode);
    if (processor == null) throw new IllegalStateException("来源缺少内容处理器: " + sourceCode);
    Object cached = record.get("ai_output");
    ProcessedEntry result;
    try {
      if (cached != null
          && processor.processorVersion() != null
          && processor.processorVersion().equals(record.get("processor_version"))) {
        result =
            processor.restore(
                fetched, String.valueOf(cached), String.valueOf(record.get("ai_model")));
      } else result = processor.process(fetched);
    } catch (RuntimeException failure) {
      throw new AiProcessingException(failure);
    }
    database.update(
        "UPDATE crawl_records SET processor_version=?, ai_model=?, ai_output=?, ai_processed_at=CASE WHEN ? IS NULL THEN ai_processed_at ELSE NOW(3) END WHERE id=? AND status='processing'",
        result.processorVersion(),
        result.aiModel(),
        result.aiOutput(),
        result.aiOutput(),
        number(record.get("id")));
    return result;
  }

  private ProcessedEntry popCidianFallback(FetchedCrawlEntry source) {
    CrawledEntry entry =
        new CrawledEntry(
            source.term(),
            source.sourceSummary(),
            source.sourceExamples(),
            PopCidianConnector.category(source.sourceCategory()),
            source.sourceCategory(),
            source.sourceTags(),
            source.sourceUrl(),
            source.sourceRecordKey(),
            source.parserVersion());
    return ProcessedEntry.imported(entry, null, null, null);
  }

  private void markDuplicate(long id, String sourceCode, CrawlerImportResult result) {
    transactions.executeWithoutResult(
        ignored -> {
          database.update(
              """
              UPDATE crawl_records
              SET status='duplicate', normalized_term=?, candidate_id=NULL,
                  duplicate_target_type=?, duplicate_target_id=?,
                  processed_at=NOW(3), lease_owner=NULL, lease_until=NULL,
                  error_type=NULL, error_message=NULL
              WHERE id=? AND status='processing'
              """,
              result.normalizedTerm(),
              result.duplicateTargetType(),
              result.duplicateTargetId(),
              id);
          database.update(
              "UPDATE crawl_checkpoints SET duplicate_count=duplicate_count+1 WHERE source_code=?",
              sourceCode);
        });
  }

  private void markIgnored(long id, String sourceCode, String reason, ProcessedEntry processed) {
    database.update(
        "UPDATE crawl_records SET status='ignored', processed_at=NOW(3), lease_owner=NULL, lease_until=NULL, processor_version=?, ai_model=?, ai_output=?, ai_processed_at=NOW(3), error_type=NULL, error_message=? WHERE id=?",
        processed.processorVersion(),
        processed.aiModel(),
        processed.aiOutput(),
        reason,
        id);
    database.update(
        "UPDATE crawl_checkpoints SET ignored_count=ignored_count+1 WHERE source_code=?",
        sourceCode);
  }

  private void resetPending(long id) {
    database.update(
        "UPDATE crawl_records SET status='pending', lease_owner=NULL, lease_until=NULL WHERE id=? AND status='processing'",
        id);
  }

  private String sourceCode(CrawlEntryProcessor processor) {
    if (processor.supports(PopCidianConnector.SOURCE_CODE)) return PopCidianConnector.SOURCE_CODE;
    if (processor.supports(RegengBaikeConnector.SOURCE_CODE))
      return RegengBaikeConnector.SOURCE_CODE;
    throw new IllegalStateException("无法识别内容处理器支持的来源: " + processor.getClass().getName());
  }

  private void fail(Map<String, Object> record, CrawlConnector connector, RuntimeException error) {
    long id = number(record.get("id"));
    String sourceCode = String.valueOf(record.get("source_code"));
    int attempt = ((Number) record.get("attempt_count")).intValue() + 1;
    if (attempt >= connector.maximumAttempts()) {
      database.update(
          """
          UPDATE crawl_records SET status='failed', lease_owner=NULL, lease_until=NULL,
              error_type=?, error_message=? WHERE id=?
          """,
          error.getClass().getSimpleName(),
          safeError(error),
          id);
      database.update(
          "UPDATE crawl_checkpoints SET failed_count=failed_count+1 WHERE source_code=?",
          sourceCode);
    } else {
      int delay = attempt == 1 ? 30 : attempt == 2 ? 120 : 600;
      database.update(
          """
          UPDATE crawl_records SET status='retry_wait', lease_owner=NULL, lease_until=NULL,
              next_attempt_at=DATE_ADD(NOW(3), INTERVAL ? SECOND),
              error_type=?, error_message=? WHERE id=?
          """,
          delay,
          error.getClass().getSimpleName(),
          safeError(error),
          id);
    }
  }

  private CrawlPointer pointer(Map<String, Object> record) {
    Object modified = record.get("source_modified_at");
    Instant modifiedAt =
        modified instanceof LocalDateTime time ? time.toInstant(ZoneOffset.UTC) : Instant.EPOCH;
    return new CrawlPointer(
        String.valueOf(record.get("source_record_key")),
        String.valueOf(record.get("source_url")),
        modifiedAt);
  }

  private void recoverStaleRecords() {
    database.update(
        """
        UPDATE crawl_records SET status='pending', lease_owner=NULL, lease_until=NULL
        WHERE status='processing' AND lease_until < NOW(3)
        """);
  }

  private long number(Object value) {
    return ((Number) value).longValue();
  }

  private static final class AiProcessingException extends RuntimeException {
    private AiProcessingException(RuntimeException cause) {
      super(cause);
    }

    private RuntimeException cause() {
      return (RuntimeException) getCause();
    }
  }

  private String safeError(Exception exception) {
    String value =
        exception.getMessage() == null
            ? exception.getClass().getSimpleName()
            : exception.getMessage();
    return value.length() > 2000 ? value.substring(0, 2000) : value;
  }
}
