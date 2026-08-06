package com.vibelex.search;

import com.vibelex.shared.persistence.MyBatisDatabase;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Executes a full index rebuild in small, durable units and cuts over the alias at the end. */
@Service
public class SearchIndexRebuildService {
  private static final Logger log = LoggerFactory.getLogger(SearchIndexRebuildService.class);
  private static final int BATCH_SIZE = 20;
  private static final int MAX_RETRIES = 5;

  private final MyBatisDatabase database;
  private final ElasticsearchGateway es;
  private final SearchIndexService index;
  private final TransactionTemplate transactions;

  public SearchIndexRebuildService(
      MyBatisDatabase database,
      ElasticsearchGateway es,
      SearchIndexService index,
      TransactionTemplate transactions) {
    this.database = database;
    this.es = es;
    this.index = index;
    this.transactions = transactions;
  }

  public synchronized Map<String, Object> start() {
    Map<String, Object> active =
        database.optionalOne(
            "SELECT id FROM search_rebuild_jobs WHERE status IN ('preparing', 'running') ORDER BY id DESC LIMIT 1");
    if (active != null) return status(((Number) active.get("id")).longValue());

    index.requireAvailable();
    String target = es.indexName() + "_" + Instant.now().toEpochMilli();
    long jobId =
        database.insert(
            "INSERT INTO search_rebuild_jobs (target_index, status, total_items) VALUES (?, 'preparing', 0)",
            target);
    try {
      es.createIndex(target);
      log.info(
          "共享义项索引全量重建准备开始 jobId={} targetIndex={}",
          jobId,
          target);
      return status(jobId);
    } catch (RuntimeException exception) {
      database.update(
          "UPDATE search_rebuild_jobs SET status='failed', last_error=?, finished_at=NOW(3) WHERE id=?",
          safeError(exception),
          jobId);
      deleteQuietly(target);
      throw exception;
    }
  }

  public Map<String, Object> status(long jobId) {
    Map<String, Object> job = database.optionalOne("SELECT * FROM search_rebuild_jobs WHERE id=?", jobId);
    if (job == null) throw new IllegalArgumentException("全量重建任务不存在: " + jobId);
    Map<String, Object> result = new LinkedHashMap<>(job);
    Map<String, Object> counts =
        database.optionalOne(
            "SELECT "
                + "SUM(status='succeeded') AS succeeded_items, "
                + "SUM(status='failed') AS failed_items, "
                + "SUM(status IN ('pending','processing')) AS pending_items "
                + "FROM search_rebuild_items WHERE job_id=?",
            jobId);
    result.put("succeeded_items", number(counts, "succeeded_items"));
    result.put("failed_items", number(counts, "failed_items"));
    result.put("pending_items", number(counts, "pending_items"));
    return result;
  }

  public Map<String, Object> latestStatus() {
    Map<String, Object> row =
        database.optionalOne("SELECT id FROM search_rebuild_jobs ORDER BY id DESC LIMIT 1");
    return row == null ? Map.of() : status(((Number) row.get("id")).longValue());
  }

  @Scheduled(fixedDelayString = "${vibelex.search.rebuild-worker.fixed-delay-millis:5000}")
  public void process() {
    recoverStaleItems();
    prepareJobs();
    for (Map<String, Object> item : claimBatch()) processItem(item);
    finalizeJobs();
  }

  public boolean blocksIncrementalSync() {
    Object count =
        database.scalar(
            "SELECT COUNT(*) FROM search_rebuild_jobs WHERE status IN ('preparing', 'running')");
    return count != null && ((Number) count).longValue() > 0;
  }

  private void prepareJobs() {
    for (Map<String, Object> job :
        database.list(
            "SELECT id, target_index FROM search_rebuild_jobs WHERE status='preparing' ORDER BY id")) {
      long jobId = ((Number) job.get("id")).longValue();
      String target = String.valueOf(job.get("target_index"));
      Object processing =
          database.scalar("SELECT COUNT(*) FROM index_sync_tasks WHERE status='processing'");
      if (processing != null && ((Number) processing).longValue() > 0) {
        log.info(
            "共享义项索引全量重建等待增量任务排空 jobId={} processingIncremental={}",
            jobId,
            processing);
        continue;
      }
      try {
        if (!es.indexExists(target)) es.createIndex(target);
        List<Map<String, Object>> memes =
            database.list(
                "SELECT id FROM meme_entries WHERE status = 'published' ORDER BY id");
        transactions.executeWithoutResult(
            ignored -> {
              Map<String, Object> locked =
                  database.optionalOne(
                      "SELECT id, status FROM search_rebuild_jobs WHERE id=? FOR UPDATE", jobId);
              if (locked == null || !"preparing".equals(locked.get("status"))) return;
              for (Map<String, Object> meme : memes)
                database.insert(
                    "INSERT INTO search_rebuild_items (job_id, meme_id) VALUES (?, ?)",
                    jobId,
                    ((Number) meme.get("id")).longValue());
              database.update(
                  "UPDATE search_rebuild_jobs SET status='running', total_items=?, started_at=NOW(3) WHERE id=? AND status='preparing'",
                  memes.size(),
                  jobId);
            });
        log.info(
            "共享义项索引全量重建开始 jobId={} targetIndex={} totalItems={}",
            jobId,
            target,
            memes.size());
      } catch (RuntimeException exception) {
        database.update(
            "UPDATE search_rebuild_jobs SET status='failed', last_error=?, finished_at=NOW(3) WHERE id=? AND status='preparing'",
            safeError(exception),
            jobId);
        deleteQuietly(target);
        log.error("共享义项索引全量重建准备失败 jobId={} targetIndex={}", jobId, target, exception);
      }
    }
  }

  private List<Map<String, Object>> claimBatch() {
    return transactions.execute(
        ignored -> {
          List<Map<String, Object>> rows =
              database.list(
                  "SELECT i.id, i.job_id, i.meme_id, i.retry_count, j.target_index "
                      + "FROM search_rebuild_items i JOIN search_rebuild_jobs j ON j.id=i.job_id "
                      + "WHERE j.status='running' AND i.status='pending' "
                      + "AND i.next_retry_at <= NOW(3) AND i.locked_at IS NULL "
                      + "ORDER BY i.id LIMIT ? FOR UPDATE SKIP LOCKED",
                  BATCH_SIZE);
          List<Map<String, Object>> claimed = new ArrayList<>();
          for (Map<String, Object> row : rows) {
            long id = ((Number) row.get("id")).longValue();
            if (database.update(
                    "UPDATE search_rebuild_items SET status='processing', locked_at=NOW(3) "
                        + "WHERE id=? AND status='pending'",
                    id)
                == 1) claimed.add(row);
          }
          return claimed;
        });
  }

  private void processItem(Map<String, Object> item) {
    long itemId = ((Number) item.get("id")).longValue();
    long jobId = ((Number) item.get("job_id")).longValue();
    long memeId = ((Number) item.get("meme_id")).longValue();
    String target = String.valueOf(item.get("target_index"));
    int retry = ((Number) item.get("retry_count")).intValue();
    try {
      index.syncMemeToIndex(memeId, target);
      database.update(
          "UPDATE search_rebuild_items SET status='succeeded', locked_at=NULL, finished_at=NOW(3), last_error=NULL WHERE id=?",
          itemId);
    } catch (RuntimeException exception) {
      int nextRetry = retry + 1;
      if (nextRetry >= MAX_RETRIES) {
        database.update(
            "UPDATE search_rebuild_items SET status='failed', retry_count=?, locked_at=NULL, finished_at=NOW(3), last_error=? WHERE id=?",
            nextRetry,
            safeError(exception),
            itemId);
      } else {
        int delay = Math.min(1800, 30 * (1 << Math.min(nextRetry - 1, 5)));
        database.update(
            "UPDATE search_rebuild_items SET status='pending', retry_count=?, locked_at=NULL, next_retry_at=DATE_ADD(NOW(3), INTERVAL ? SECOND), last_error=? WHERE id=?",
            nextRetry,
            delay,
            safeError(exception),
            itemId);
      }
      log.warn(
          "全量重建义项处理失败 jobId={} itemId={} memeId={} retry={}",
          jobId,
          itemId,
          memeId,
          nextRetry,
          exception);
    }
  }

  private void finalizeJobs() {
    for (Map<String, Object> row :
        database.list("SELECT id, target_index FROM search_rebuild_jobs WHERE status='running'")) {
      long jobId = ((Number) row.get("id")).longValue();
      String target = String.valueOf(row.get("target_index"));
      Map<String, Object> counts =
          database.optionalOne(
              "SELECT "
                  + "SUM(status='succeeded') AS succeeded_items, "
                  + "SUM(status='failed') AS failed_items, "
                  + "SUM(status IN ('pending','processing')) AS pending_items "
                  + "FROM search_rebuild_items WHERE job_id=?",
              jobId);
      long failed = number(counts, "failed_items");
      long pending = number(counts, "pending_items");
      if (failed > 0) {
        database.update(
            "UPDATE search_rebuild_jobs SET status='failed', failed_items=?, last_error='存在无法完成的重建子任务', finished_at=NOW(3) WHERE id=? AND status='running'",
            failed,
            jobId);
        deleteQuietly(target);
      } else if (pending == 0) {
        try {
          List<String> previous = es.switchAlias(target);
          List<String> deleted = new ArrayList<>();
          for (String old : previous) {
            try {
              es.deleteIndex(old);
              deleted.add(old);
            } catch (RuntimeException exception) {
              log.error("全量重建已切换别名，但旧索引删除失败 index={}", old, exception);
            }
          }
          database.update(
              "UPDATE search_rebuild_jobs SET status='succeeded', succeeded_items=?, failed_items=0, finished_at=NOW(3) WHERE id=? AND status='running'",
              number(counts, "succeeded_items"),
              jobId);
          log.info(
              "共享义项索引全量重建完成 jobId={} targetIndex={} succeededItems={} deletedOldIndices={}",
              jobId,
              target,
              number(counts, "succeeded_items"),
              deleted);
        } catch (RuntimeException exception) {
          database.update(
              "UPDATE search_rebuild_jobs SET status='failed', last_error=? WHERE id=? AND status='running'",
              safeError(exception),
              jobId);
          log.error("共享义项索引全量重建别名切换失败 jobId={} targetIndex={}", jobId, target, exception);
        }
      } else {
        log.info(
            "共享义项索引全量重建进度 jobId={} targetIndex={} succeededItems={} pendingItems={} failedItems={}",
            jobId,
            target,
            number(counts, "succeeded_items"),
            pending,
            failed);
      }
    }
  }

  private void recoverStaleItems() {
    database.update(
        "UPDATE search_rebuild_items SET status='pending', locked_at=NULL "
            + "WHERE status='processing' AND locked_at < DATE_SUB(NOW(3), INTERVAL 10 MINUTE)");
  }

  private long number(Map<String, Object> row, String key) {
    Object value = row == null ? null : row.get(key);
    return value == null ? 0 : ((Number) value).longValue();
  }

  private String safeError(Exception exception) {
    String value = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    return value.length() > 2000 ? value.substring(0, 2000) : value;
  }

  private void deleteQuietly(String indexName) {
    try {
      es.deleteIndex(indexName);
    } catch (RuntimeException cleanupFailure) {
      log.error("全量重建失败，临时索引清理失败 index={}", indexName, cleanupFailure);
    }
  }
}
