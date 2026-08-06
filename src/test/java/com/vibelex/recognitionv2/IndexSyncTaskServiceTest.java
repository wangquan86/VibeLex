package com.vibelex.recognitionv2;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vibelex.search.SearchIndexNotReadyException;
import com.vibelex.search.SearchIndexRebuildService;
import com.vibelex.search.SearchIndexService;
import com.vibelex.shared.persistence.MyBatisDatabase;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

class IndexSyncTaskServiceTest {
  private MyBatisDatabase database;
  private SearchIndexService index;
  private SearchIndexRebuildService rebuilds;
  private IndexSyncTaskService service;

  @BeforeEach
  void setUp() {
    database = mock(MyBatisDatabase.class);
    index = mock(SearchIndexService.class);
    rebuilds = mock(SearchIndexRebuildService.class);
    service =
        new IndexSyncTaskService(database, index, rebuilds, mock(TransactionTemplate.class));
  }

  @Test
  void fullRebuildPausesConsumptionWithoutBlockingEnqueue() {
    when(rebuilds.blocksIncrementalSync()).thenReturn(true);

    service.process();
    service.enqueue(101L, "UPSERT");

    verify(database, never()).list(any(), any(Object[].class));
    verify(database)
        .update(
            argThat(sql -> sql.contains("INSERT INTO index_sync_tasks")), any(Object[].class));
  }

  @Test
  void dependencyFailureReturnsTaskToPendingInsteadOfMarkingSuccess() {
    doThrow(new SearchIndexNotReadyException("mapping requires rebuild"))
        .when(index)
        .syncMeme(101L);

    service.processTask(Map.of("id", 1L, "meme_id", 101L, "operation", "UPSERT", "retry_count", 0));

    verify(database, never())
        .update(argThat(sql -> sql.contains("status='succeeded'")), any(Object[].class));
    verify(database)
        .update(
            argThat(
                sql ->
                    sql.contains("status='pending'")
                        && sql.contains("status='processing'")
                        && !sql.contains("retry_count")),
            any(Object[].class));
  }

  @Test
  void successfulWorkerCannotOverwriteAConcurrentRequeue() {
    service.processTask(Map.of("id", 1L, "meme_id", 101L, "operation", "UPSERT", "retry_count", 0));

    verify(database)
        .update(
            argThat(
                sql ->
                    sql.contains("status='succeeded'")
                        && sql.contains("WHERE id=? AND status='processing'")),
            any(Object[].class));
    verify(database)
        .update(
            argThat(
                sql -> sql.contains("status='pending'") && sql.contains("locked_at IS NOT NULL")),
            any(Object[].class));
  }

  @Test
  void enqueueAlwaysRequestsAnotherRunForAnExistingTask() {
    service.enqueue(101L, "UPSERT");

    verify(database)
        .update(
            argThat(
                sql -> sql.contains("status='pending'") && sql.contains("IF(status='processing'")),
            any(Object[].class));
  }

  @Test
  void taskListIncludesTheNormalizedEntryTerm() {
    when(database.scalar(any(), any(Object[].class))).thenReturn(0L);
    when(database.list(any(), any(Object[].class))).thenReturn(List.of());

    service.list("all", 1, 20);

    verify(database)
        .list(
            argThat(
                sql ->
                    sql.contains("m.normalized_term")
                        && sql.contains("LEFT JOIN meme_entries m ON m.id=t.meme_id")),
            any(Object[].class));
  }
}
