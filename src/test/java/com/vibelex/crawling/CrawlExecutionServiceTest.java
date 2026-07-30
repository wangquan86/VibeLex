package com.vibelex.crawling;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vibelex.shared.persistence.MyBatisDatabase;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

class CrawlExecutionServiceTest {
  private MyBatisDatabase database;
  private CrawlExecutionService service;

  @BeforeEach
  void setUp() {
    database = mock(MyBatisDatabase.class);
    service =
        new CrawlExecutionService(
            database, new ObjectMapper(), new CrawlProperties(), List.of(mockConnector("fixture")));
  }

  @Test
  void doesNotAdvanceCheckpointWhenAnyRecordFailed() {
    when(database.scalar(anyString(), any(Object[].class))).thenReturn(0L, 2L);

    service.finishIfComplete("fixture");

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(database).update(sql.capture(), any(Object[].class));
    org.assertj.core.api.Assertions.assertThat(sql.getValue())
        .contains("current_status='partial'")
        .doesNotContain("checkpoint=pending_checkpoint");
  }

  @Test
  void advancesCheckpointOnlyAfterAllRecordsReachTerminalState() {
    when(database.scalar(anyString(), any(Object[].class))).thenReturn(0L, 0L);

    service.finishIfComplete("fixture");

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(database).update(sql.capture(), any(Object[].class));
    org.assertj.core.api.Assertions.assertThat(sql.getValue())
        .contains("checkpoint=pending_checkpoint")
        .contains("current_status='idle'");
  }

  @Test
  void leavesRunningTaskUntouchedWhileRecordsAreRetryable() {
    when(database.scalar(anyString(), any(Object[].class))).thenReturn(1L);

    service.finishIfComplete("fixture");

    verify(database, never()).update(anyString(), any(Object[].class));
  }

  @Test
  void unifiedSyncUsesEmptyCheckpointAndAutomaticallyRequeuesFailures() {
    CrawlConnector connector = mockConnector(PopCidianConnector.SOURCE_CODE);
    CrawlProperties properties = new CrawlProperties();
    properties.setEnabled(true);
    properties.getPopcidian().setEnabled(true);
    service =
        new CrawlExecutionService(database, new ObjectMapper(), properties, List.of(connector));
    when(database.update(anyString(), any(Object[].class))).thenReturn(1);
    when(database.scalar(anyString(), any(Object[].class))).thenReturn(null, 0L, 0L, 0L);
    when(database.one(anyString(), any(Object[].class)))
        .thenReturn(Map.of("source_code", PopCidianConnector.SOURCE_CODE));
    when(database.list(anyString(), any(Object[].class))).thenReturn(List.of());
    when(connector.enumerate(isNull()))
        .thenReturn(new CrawlConnector.EnumerationResult(List.of(), null));

    Map<String, Object> result = service.startSync(PopCidianConnector.SOURCE_CODE);

    verify(connector).enumerate(isNull());
    org.assertj.core.api.Assertions.assertThat(result.get("sync_outcome")).isEqualTo("no_change");
    ArgumentCaptor<String> updates = ArgumentCaptor.forClass(String.class);
    verify(database, org.mockito.Mockito.atLeastOnce())
        .update(updates.capture(), any(Object[].class));
    org.assertj.core.api.Assertions.assertThat(updates.getAllValues())
        .anyMatch(sql -> sql.contains("status='failed'"));
  }

  private CrawlConnector mockConnector(String sourceCode) {
    CrawlConnector connector = mock(CrawlConnector.class);
    when(connector.sourceCode()).thenReturn(sourceCode);
    when(connector.sourceName()).thenReturn("测试来源");
    return connector;
  }
}
