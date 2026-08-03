package com.vibelex.crawling;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vibelex.shared.persistence.MyBatisDatabase;
import java.time.Instant;
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
        .contains("CASE WHEN validation_run=1 THEN checkpoint ELSE pending_checkpoint END")
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
    PopCidianAiEnricher enricher = mock(PopCidianAiEnricher.class);
    service =
        new CrawlExecutionService(
            database, new ObjectMapper(), properties, List.of(connector), null, enricher);
    when(database.update(anyString(), any(Object[].class))).thenReturn(1);
    when(database.scalar(anyString(), any(Object[].class))).thenReturn(null, 0L, 0L, 0L);
    when(database.one(anyString(), any(Object[].class)))
        .thenReturn(Map.of("source_code", PopCidianConnector.SOURCE_CODE));
    when(database.list(anyString(), any(Object[].class))).thenReturn(List.of());
    when(connector.enumerate(isNull()))
        .thenReturn(new CrawlConnector.EnumerationResult(List.of(), null));

    Map<String, Object> result = service.startSync(PopCidianConnector.SOURCE_CODE);

    verify(enricher).validateConfiguration();
    verify(connector).enumerate(isNull());
    org.assertj.core.api.Assertions.assertThat(result.get("sync_outcome")).isEqualTo("no_change");
    ArgumentCaptor<String> updates = ArgumentCaptor.forClass(String.class);
    verify(database, org.mockito.Mockito.atLeastOnce())
        .update(updates.capture(), any(Object[].class));
    org.assertj.core.api.Assertions.assertThat(updates.getAllValues())
        .anyMatch(sql -> sql.contains("status='failed'"));
  }

  @Test
  void startsBoundedRegengValidationWithoutAdvancingCheckpoint() {
    CrawlConnector connector = mockConnector(RegengBaikeConnector.SOURCE_CODE);
    RegengBaikeAiExtractor extractor = mock(RegengBaikeAiExtractor.class);
    CrawlProperties properties = new CrawlProperties();
    properties.setEnabled(true);
    properties.getRegengbaike().setEnabled(true);
    service =
        new CrawlExecutionService(
            database, new ObjectMapper(), properties, List.of(connector), extractor);
    when(database.update(anyString(), any(Object[].class))).thenReturn(1);
    when(database.scalar(anyString(), any(Object[].class))).thenReturn(2L, 2L);
    when(database.one(anyString(), any(Object[].class)))
        .thenReturn(Map.of("source_code", RegengBaikeConnector.SOURCE_CODE));
    when(database.list(anyString(), any(Object[].class))).thenReturn(List.of());
    when(connector.enumerate(isNull()))
        .thenReturn(
            new CrawlConnector.EnumerationResult(
                List.of(
                    new CrawlConnector.CrawlPointer(
                        "2", "https://regengbaike.com/2.html", Instant.EPOCH),
                    new CrawlConnector.CrawlPointer(
                        "1", "https://regengbaike.com/1.html", Instant.EPOCH)),
                new ObjectMapper().createObjectNode().put("maximumArchiveId", 2)));

    Map<String, Object> result = service.startValidation(RegengBaikeConnector.SOURCE_CODE, 2);

    verify(extractor).validateConfiguration();
    verify(connector).enumerate(isNull());
    org.assertj.core.api.Assertions.assertThat(result)
        .containsEntry("validation_outcome", "started")
        .containsEntry("sample_count", 2)
        .containsEntry("queued_count", 2L)
        .containsEntry("checkpoint_will_advance", false);
    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(database, org.mockito.Mockito.atLeastOnce()).update(sql.capture(), any(Object[].class));
    org.assertj.core.api.Assertions.assertThat(sql.getAllValues())
        .anyMatch(value -> value.contains("validation_run=1"));
  }

  @Test
  void startsPopCidianValidationWithNonNumericRecordKeys() {
    CrawlConnector connector = mockConnector(PopCidianConnector.SOURCE_CODE);
    PopCidianAiEnricher enricher = mock(PopCidianAiEnricher.class);
    CrawlProperties properties = new CrawlProperties();
    properties.setEnabled(true);
    properties.getPopcidian().setEnabled(true);
    service =
        new CrawlExecutionService(
            database, new ObjectMapper(), properties, List.of(connector), null, enricher);
    when(database.update(anyString(), any(Object[].class))).thenReturn(1);
    when(database.scalar(anyString(), any(Object[].class))).thenReturn(2L, 2L);
    when(database.one(anyString(), any(Object[].class)))
        .thenReturn(Map.of("source_code", PopCidianConnector.SOURCE_CODE));
    when(database.list(anyString(), any(Object[].class))).thenReturn(List.of());
    when(connector.enumerate(isNull()))
        .thenReturn(
            new CrawlConnector.EnumerationResult(
                List.of(
                    new CrawlConnector.CrawlPointer(
                        "我有个朋友", "https://www.popcidian.com/entry/friend", Instant.EPOCH),
                    new CrawlConnector.CrawlPointer(
                        "白人饭", "https://www.popcidian.com/entry/meal", Instant.EPOCH)),
                null));

    Map<String, Object> result = service.startValidation(PopCidianConnector.SOURCE_CODE, 2);

    verify(enricher).validateConfiguration();
    verify(connector).enumerate(isNull());
    org.assertj.core.api.Assertions.assertThat(result)
        .containsEntry("validation_outcome", "started")
        .containsEntry("sample_count", 2)
        .containsEntry("checkpoint_will_advance", false);
  }

  @Test
  void rejectsValidationCountsAboveSafetyLimit() {
    assertThatThrownBy(() -> service.startValidation(RegengBaikeConnector.SOURCE_CODE, 51))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("1 到 50");
  }

  private CrawlConnector mockConnector(String sourceCode) {
    CrawlConnector connector = mock(CrawlConnector.class);
    when(connector.sourceCode()).thenReturn(sourceCode);
    when(connector.sourceName()).thenReturn("测试来源");
    return connector;
  }
}
