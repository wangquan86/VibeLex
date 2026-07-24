package com.vibelex.recognitionv2;

import com.vibelex.shared.persistence.MyBatisDatabase;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class IndexSyncTaskService {
  private static final Logger log = LoggerFactory.getLogger(IndexSyncTaskService.class);
  private static final int BATCH_SIZE = 20;
  private static final int MAX_RETRIES = 5;
  private final MyBatisDatabase database;
  private final SemanticIndexService index;
  private final TransactionTemplate transactions;

  public IndexSyncTaskService(
      MyBatisDatabase database, SemanticIndexService index, TransactionTemplate transactions) {
    this.database = database;
    this.index = index;
    this.transactions = transactions;
  }

  public void enqueueAfterCommit(long memeId, String operation) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      enqueue(memeId, operation);
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            enqueue(memeId, operation);
          }
        });
  }

  public void enqueue(long memeId, String operation) {
    database.update(
        """
        INSERT INTO index_sync_tasks(meme_id, operation, status, retry_count, next_retry_at)
        VALUES (?, ?, 'pending', 0, NOW(3))
        ON DUPLICATE KEY UPDATE
          operation=VALUES(operation),
          status=IF(status='processing', 'processing', 'pending'),
          next_retry_at=NOW(3), last_error=NULL, finished_at=NULL
        """,
        memeId,
        operation);
  }

  public Map<String, Object> list(String status, int page, int size) {
    int safePage = Math.max(1, page), safeSize = Math.max(10, Math.min(100, size));
    String selected = status == null || status.isBlank() ? "all" : status;
    String where = "all".equals(selected) ? "" : " WHERE status=?";
    Object[] args = "all".equals(selected) ? new Object[] {} : new Object[] {selected};
    Object total = database.scalar("SELECT COUNT(*) FROM index_sync_tasks" + where, args);
    List<Map<String, Object>> items =
        "all".equals(selected)
            ? database.list("SELECT * FROM index_sync_tasks ORDER BY id DESC LIMIT ? OFFSET ?", safeSize, (safePage - 1) * safeSize)
            : database.list("SELECT * FROM index_sync_tasks" + where + " ORDER BY id DESC LIMIT ? OFFSET ?", selected, safeSize, (safePage - 1) * safeSize);
    List<Map<String, Object>> counts = database.list("SELECT status, COUNT(*) AS count FROM index_sync_tasks GROUP BY status");
    return Map.of("items", items, "page", safePage, "size", safeSize, "totalElements", ((Number) total).longValue(), "summary", counts);
  }

  public void retry(long id) {
    int changed = database.update("UPDATE index_sync_tasks SET status='pending', retry_count=0, next_retry_at=NOW(3), last_error=NULL, finished_at=NULL WHERE id=? AND status='failed'", id);
    if (changed == 0) throw new IllegalStateException("仅失败任务可以重新入队");
  }

  @Scheduled(fixedDelayString = "${vibelex.recognition.v2.index-worker.fixed-delay-millis:5000}")
  public void process() {
    recoverStaleTasks();
    for (Map<String, Object> task : claimBatch()) processTask(task);
  }

  private List<Map<String, Object>> claimBatch() {
    return transactions.execute(
        ignored -> {
          List<Map<String, Object>> tasks =
              database.list(
                  """
                  SELECT id, meme_id, operation, retry_count
                  FROM index_sync_tasks
                  WHERE status='pending' AND next_retry_at <= NOW(3)
                  ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED
                  """,
                  BATCH_SIZE);
          List<Map<String, Object>> claimed = new ArrayList<>();
          for (Map<String, Object> task : tasks) {
            int changed =
                database.update(
                    "UPDATE index_sync_tasks SET status='processing', locked_at=NOW(3) WHERE id=? AND status='pending'",
                    ((Number) task.get("id")).longValue());
            if (changed == 1) claimed.add(task);
          }
          return claimed;
        });
  }

  private void processTask(Map<String, Object> task) {
    long id = ((Number) task.get("id")).longValue();
    long memeId = ((Number) task.get("meme_id")).longValue();
    int retry = ((Number) task.get("retry_count")).intValue();
    try {
      if ("DELETE".equals(task.get("operation"))) index.deleteMeme(memeId);
      else index.syncMeme(memeId);
      database.update(
          "UPDATE index_sync_tasks SET status='succeeded', finished_at=NOW(3), last_error=NULL WHERE id=?", id);
    } catch (RuntimeException ex) {
      int nextRetry = retry + 1;
      if (nextRetry >= MAX_RETRIES) {
        database.update(
            "UPDATE index_sync_tasks SET status='failed', retry_count=?, last_error=? WHERE id=?",
            nextRetry, safeError(ex), id);
      } else {
        int delay = Math.min(1800, 60 * (1 << Math.min(nextRetry - 1, 5)));
        database.update(
            "UPDATE index_sync_tasks SET status='pending', retry_count=?, next_retry_at=DATE_ADD(NOW(3), INTERVAL ? SECOND), last_error=? WHERE id=?",
            nextRetry, delay, safeError(ex), id);
      }
      log.warn("V2 索引任务失败 id={} memeId={} retry={}", id, memeId, nextRetry, ex);
    }
  }

  private void recoverStaleTasks() {
    database.update(
        """
        UPDATE index_sync_tasks SET status='pending', locked_at=NULL
        WHERE status='processing' AND locked_at < DATE_SUB(NOW(3), INTERVAL 10 MINUTE)
        """);
  }

  private String safeError(Exception ex) {
    String value = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
    return value.length() > 2000 ? value.substring(0, 2000) : value;
  }
}
