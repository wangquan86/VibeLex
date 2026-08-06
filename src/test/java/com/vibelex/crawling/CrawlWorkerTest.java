package com.vibelex.crawling;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vibelex.candidatediscovery.application.CandidateService;
import com.vibelex.candidatediscovery.application.CandidateService.CrawlerImportResult;
import com.vibelex.crawling.CrawlConnector.CrawledEntry;
import com.vibelex.crawling.CrawlConnector.FetchedCrawlEntry;
import com.vibelex.shared.persistence.MyBatisDatabase;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

class CrawlWorkerTest {
  private MyBatisDatabase database;
  private CrawlExecutionService executions;
  private CandidateService candidates;
  private CrawlConnector connector;
  private CrawlEntryProcessor processor;
  private CrawlWorker worker;
  private ObjectMapper mapper;

  @BeforeEach
  void setUp() {
    database = mock(MyBatisDatabase.class);
    executions = mock(CrawlExecutionService.class);
    candidates = mock(CandidateService.class);
    connector = mock(CrawlConnector.class);
    processor = mock(CrawlEntryProcessor.class);
    mapper = new ObjectMapper();
    when(processor.supports("popcidian")).thenReturn(true);
    when(connector.sourceName()).thenReturn("波普词典");
    when(connector.maximumAttempts()).thenReturn(3);
    when(executions.connectorForWorker("popcidian")).thenReturn(connector);
    when(executions.isRunning("popcidian")).thenReturn(true);
    when(database.optionalOne(anyString(), any(Object[].class))).thenReturn(null);
    CrawlProperties properties = new CrawlProperties();
    properties.setEnabled(true);
    properties.getWorker().setActorId("system");
    worker =
        new CrawlWorker(
            database,
            properties,
            executions,
            candidates,
            executingTransactions(),
            mapper,
            List.of(processor));
  }

  @Test
  void duplicateTermSkipsAiProcessing() throws Exception {
    when(candidates.precheckCrawlerDuplicate("测试词"))
        .thenReturn(new CrawlerImportResult("duplicate", null, "meme", 7L, "测试词"));

    worker.processRecord(record(0));

    verify(processor, never()).process(any());
    verify(database)
        .update(argThat(sql -> sql.contains("SET status='duplicate'")), any(Object[].class));
  }

  @Test
  void scheduledWorkersClaimEachCrawlerSourceIndependently() {
    worker.processPopCidian();
    worker.processRegengBaike();

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Object[]> parameters = ArgumentCaptor.forClass(Object[].class);
    verify(database, times(2)).optionalOne(sql.capture(), parameters.capture());
    org.assertj.core.api.Assertions.assertThat(sql.getAllValues())
        .allMatch(value -> value.contains("AND r.source_code=?"));
    org.assertj.core.api.Assertions.assertThat(parameters.getAllValues())
        .extracting(values -> values[0])
        .containsExactly("popcidian", "regengbaike");
  }

  @Test
  void createsCandidateFromSourceAfterThirdPopCidianAiFailure() throws Exception {
    when(candidates.precheckCrawlerDuplicate("测试词")).thenReturn(null);
    when(processor.process(any())).thenThrow(new IllegalStateException("AI unavailable"));
    when(candidates.createFromCrawler(anyString(), anyString(), any(), anyString()))
        .thenReturn(new CrawlerImportResult("imported", 42L, null, null, "测试词"));

    worker.processRecord(record(2));

    ArgumentCaptor<CrawledEntry> entry = ArgumentCaptor.forClass(CrawledEntry.class);
    verify(candidates)
        .createFromCrawler(
            org.mockito.ArgumentMatchers.eq("popcidian"),
            org.mockito.ArgumentMatchers.eq("波普词典"),
            entry.capture(),
            org.mockito.ArgumentMatchers.eq("system"));
    org.assertj.core.api.Assertions.assertThat(entry.getValue().definition()).isEqualTo("来源原始释义");
    org.assertj.core.api.Assertions.assertThat(entry.getValue().examples()).containsExactly("来源例句");
    ArgumentCaptor<Object[]> completion = ArgumentCaptor.forClass(Object[].class);
    verify(database).update(argThat(sql -> sql.contains("SET status=?")), completion.capture());
    org.assertj.core.api.Assertions.assertThat(completion.getValue()[5])
        .isEqualTo("AiEnrichmentFallback");
    org.assertj.core.api.Assertions.assertThat(completion.getValue()[6])
        .isEqualTo("AI丰富连续3次失败，已使用来源原始内容进入候选：AI unavailable");
  }

  @Test
  void keepsRegengBaikeFailedAfterThirdAiExtractionFailure() throws Exception {
    CrawlEntryProcessor regengProcessor = mock(CrawlEntryProcessor.class);
    when(regengProcessor.supports("popcidian")).thenReturn(false);
    when(regengProcessor.supports("regengbaike")).thenReturn(true);
    when(regengProcessor.process(any())).thenThrow(new IllegalStateException("AI unavailable"));
    CrawlConnector regengConnector = mock(CrawlConnector.class);
    when(regengConnector.maximumAttempts()).thenReturn(3);
    when(executions.connectorForWorker("regengbaike")).thenReturn(regengConnector);
    when(executions.isRunning("regengbaike")).thenReturn(true);
    when(candidates.precheckCrawlerDuplicate("测试词")).thenReturn(null);
    CrawlProperties properties = new CrawlProperties();
    properties.getWorker().setActorId("system");
    CrawlWorker regengWorker =
        new CrawlWorker(
            database,
            properties,
            executions,
            candidates,
            executingTransactions(),
            mapper,
            List.of(regengProcessor));

    workerRecord(regengWorker, "regengbaike", 2);

    verify(candidates, never()).createFromCrawler(anyString(), anyString(), any(), anyString());
    verify(database)
        .update(argThat(sql -> sql.contains("SET status='failed'")), any(Object[].class));
  }

  private Map<String, Object> record(int attempts) throws Exception {
    return record("popcidian", attempts);
  }

  private void workerRecord(CrawlWorker selectedWorker, String sourceCode, int attempts)
      throws Exception {
    selectedWorker.processRecord(record(sourceCode, attempts));
  }

  private Map<String, Object> record(String sourceCode, int attempts) throws Exception {
    FetchedCrawlEntry fetched =
        new FetchedCrawlEntry(
            "测试词",
            "来源原始释义",
            "",
            List.of("来源例句"),
            "网络用语",
            List.of("网络"),
            "https://example.test/term",
            "source-key",
            Instant.EPOCH,
            "v1");
    return Map.of(
        "id",
        1L,
        "source_code",
        sourceCode,
        "source_record_key",
        "source-key",
        "source_url",
        "https://example.test/term",
        "source_payload",
        mapper.writeValueAsString(fetched),
        "attempt_count",
        attempts);
  }

  @SuppressWarnings("unchecked")
  private TransactionTemplate executingTransactions() {
    TransactionTemplate transactions = mock(TransactionTemplate.class);
    doAnswer(
            invocation -> {
              TransactionCallback<Object> action = invocation.getArgument(0);
              return action.doInTransaction(mock(TransactionStatus.class));
            })
        .when(transactions)
        .execute(any());
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
