package com.vibelex.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vibelex.shared.persistence.MyBatisDatabase;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class SearchIndexRebuildServiceTest {
  private MyBatisDatabase database;
  private ElasticsearchGateway es;
  private SearchIndexService index;
  private SearchIndexRebuildService service;

  @BeforeEach
  void setUp() {
    database = Mockito.mock(MyBatisDatabase.class);
    es = Mockito.mock(ElasticsearchGateway.class);
    index = Mockito.mock(SearchIndexService.class);
    TransactionTemplate transactions = Mockito.mock(TransactionTemplate.class);
    service = new SearchIndexRebuildService(database, es, index, transactions);
    when(transactions.execute(Mockito.any()))
        .thenAnswer(
            invocation ->
                ((TransactionCallback<?>) invocation.getArgument(0)).doInTransaction(null));
    when(es.indexName()).thenReturn("vibelex_sense");
    when(database.optionalOne(anyString(), any(Object[].class)))
        .thenAnswer(
            invocation -> {
              String sql = invocation.getArgument(0);
              if (sql.startsWith("SELECT id FROM search_rebuild_jobs")) return null;
              if (sql.startsWith("SELECT * FROM search_rebuild_jobs"))
                return Map.of(
                    "id", 7L,
                    "status", "preparing",
                    "target_index", "vibelex_sense_123",
                    "total_items", 0);
              if (sql.contains("SUM(status='succeeded')"))
                return Map.of("succeeded_items", 0L, "failed_items", 0L, "pending_items", 0L);
              return null;
            });
    when(database.list(anyString(), any(Object[].class)))
        .thenReturn(List.of(Map.of("id", 101L), Map.of("id", 102L)));
    AtomicLong ids = new AtomicLong(1);
    when(database.insert(anyString(), any(Object[].class))).thenAnswer(ignored -> ids.getAndIncrement());
    when(database.update(anyString(), any(Object[].class))).thenReturn(1);
  }

  @Test
  void startsPreparingJobWithoutDoingEmbeddingInline() {
    when(database.insert(anyString(), any(Object[].class))).thenAnswer(invocation -> 7L);

    Map<String, Object> result = service.start();

    verify(index).requireAvailable();
    ArgumentCaptor<String> target = ArgumentCaptor.forClass(String.class);
    verify(es).createIndex(target.capture());
    assertThat(target.getValue()).startsWith("vibelex_sense_");
    verify(database).insert(anyString(), any(Object[].class));
    verify(index, never()).syncMemeToIndex(Mockito.anyLong(), anyString());
    assertThat(result).containsEntry("id", 7L).containsEntry("status", "preparing");
  }

  @Test
  void returnsExistingActiveJobWithoutCreatingAnotherIndex() {
    when(database.optionalOne(anyString(), any(Object[].class)))
        .thenAnswer(
            invocation -> {
              String sql = invocation.getArgument(0);
              if (sql.startsWith("SELECT id FROM search_rebuild_jobs")) return Map.of("id", 9L);
              if (sql.startsWith("SELECT * FROM search_rebuild_jobs"))
                return Map.of(
                    "id", 9L,
                    "status", "preparing",
                    "target_index", "vibelex_sense_123",
                    "total_items", 0);
              if (sql.contains("SUM(status='succeeded')"))
                return Map.of("succeeded_items", 0L, "failed_items", 0L, "pending_items", 0L);
              return null;
            });

    Map<String, Object> result = service.start();

    verify(es, never()).createIndex(anyString());
    assertThat(result).containsEntry("id", 9L).containsEntry("status", "preparing");
  }
}
