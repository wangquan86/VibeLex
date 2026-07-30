package com.vibelex.crawling;

import com.vibelex.candidatediscovery.application.CandidateService;
import com.vibelex.candidatediscovery.application.CandidateService.CrawlerImportResult;
import com.vibelex.crawling.CrawlConnector.CrawlPointer;
import com.vibelex.crawling.CrawlConnector.CrawledEntry;
import com.vibelex.shared.persistence.MyBatisDatabase;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class CrawlWorker {
  private static final Logger log = LoggerFactory.getLogger(CrawlWorker.class);
  private final MyBatisDatabase database;
  private final CrawlProperties properties;
  private final CrawlExecutionService executions;
  private final CandidateService candidates;
  private final TransactionTemplate transactions;
  private final String workerId = "crawler-" + UUID.randomUUID();

  public CrawlWorker(
      MyBatisDatabase database,
      CrawlProperties properties,
      CrawlExecutionService executions,
      CandidateService candidates,
      TransactionTemplate transactions) {
    this.database = database;
    this.properties = properties;
    this.executions = executions;
    this.candidates = candidates;
    this.transactions = transactions;
  }

  @Scheduled(fixedDelayString = "${vibelex.crawling.worker.fixed-delay-millis:3000}")
  public void process() {
    if (!properties.isEnabled()) return;
    recoverStaleRecords();
    executions.recoverStalePlanning();
    Map<String, Object> record = claim();
    if (record == null) return;
    processRecord(record);
  }

  private Map<String, Object> claim() {
    return transactions.execute(
        ignored -> {
          Map<String, Object> record =
              database.optionalOne(
                  """
                  SELECT r.* FROM crawl_records r
                  JOIN crawl_checkpoints c ON c.source_code=r.source_code
                  WHERE c.current_status='running'
                    AND r.status IN ('pending', 'retry_wait')
                    AND (r.next_attempt_at IS NULL OR r.next_attempt_at <= NOW(3))
                  ORDER BY r.id
                  LIMIT 1 FOR UPDATE SKIP LOCKED
                  """);
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

  private void processRecord(Map<String, Object> record) {
    long id = number(record.get("id"));
    String sourceCode = String.valueOf(record.get("source_code"));
    CrawlConnector connector = executions.connectorForWorker(sourceCode);
    try {
      CrawledEntry entry = connector.fetch(pointer(record));
      transactions.executeWithoutResult(
          ignored -> {
            CrawlerImportResult result =
                candidates.createFromCrawler(
                    sourceCode, connector.sourceName(), entry, properties.getWorker().getActorId());
            database.update(
                """
                UPDATE crawl_records
                SET status=?, normalized_term=?, candidate_id=?,
                    duplicate_target_type=?, duplicate_target_id=?,
                    processed_at=NOW(3), lease_owner=NULL, lease_until=NULL,
                    error_type=NULL, error_message=NULL
                WHERE id=? AND status='processing'
                """,
                result.status(),
                result.normalizedTerm(),
                result.candidateId(),
                result.duplicateTargetType(),
                result.duplicateTargetId(),
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

  private String safeError(Exception exception) {
    String value =
        exception.getMessage() == null
            ? exception.getClass().getSimpleName()
            : exception.getMessage();
    return value.length() > 2000 ? value.substring(0, 2000) : value;
  }
}
