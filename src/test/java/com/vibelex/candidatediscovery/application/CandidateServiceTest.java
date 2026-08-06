package com.vibelex.candidatediscovery.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.vibelex.actorcontext.CurrentActorProvider;
import com.vibelex.candidatediscovery.domain.TermNormalizer;
import com.vibelex.crawling.CrawlConnector.CrawledEntry;
import com.vibelex.crawling.CrawlConnector.OriginReference;
import com.vibelex.llm.AiVariantGenerator;
import com.vibelex.reviewworkflow.application.ChangeSetService;
import com.vibelex.shared.persistence.MyBatisDatabase;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class CandidateServiceTest {

  private MyBatisDatabase database;
  private CandidateService service;
  private CurrentActorProvider actor;

  @BeforeEach
  void setUp() {
    database = mock(MyBatisDatabase.class);
    actor = mock(CurrentActorProvider.class);
    service =
        new CandidateService(
            database,
            mock(ObjectMapper.class),
            actor,
            mock(TermNormalizer.class),
            mock(ChangeSetService.class),
            mock(AiVariantGenerator.class));
  }

  @Test
  void returnsPagedCandidatesAndMetadata() {
    when(database.scalar(anyString(), any(Object[].class))).thenReturn(45L);
    when(database.list(anyString(), any(Object[].class)))
        .thenReturn(List.of(Map.of("id", 21L, "status", "editing")));

    Map<String, Object> result = service.list("editing", 2, 20, "测试", "CHIME");

    assertThat(result.get("page")).isEqualTo(2);
    assertThat(result.get("size")).isEqualTo(20);
    assertThat(result.get("totalElements")).isEqualTo(45L);
    assertThat(result.get("totalPages")).isEqualTo(3L);
    assertThat((List<?>) result.get("items")).hasSize(1);
  }

  @Test
  void limitsPageSizeToOneHundred() {
    when(database.scalar(anyString(), any(Object[].class))).thenReturn(0L);
    when(database.list(anyString(), any(Object[].class))).thenReturn(List.of());

    Map<String, Object> result = service.list("all", 1, 1000, "", "");

    assertThat(result.get("size")).isEqualTo(100);
  }

  @Test
  void rejectsUnsupportedStatusBeforeQueryingDatabase() {
    assertThatThrownBy(() -> service.list("deleted", 1, 20, "", ""))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不支持的候选状态");
    verifyNoInteractions(database);
  }

  @Test
  void doesNotAllowEditingCandidateUnderReview() {
    when(database.optionalOne(anyString(), any(Object[].class)))
        .thenReturn(
            Map.of(
                "id", 1L,
                "status", "pending_review",
                "processing_note", "{}"));

    assertThatThrownBy(
            () -> service.update(1L, "词条", "释义", "other", "", List.of(), false, false, ""))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("编辑中或已退回");
  }

  @Test
  void clearsAiOriginReferencesWhenAnEditorChangesTheOrigin() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    TermNormalizer normalizer = mock(TermNormalizer.class);
    when(normalizer.normalize("破防", "zh-CN")).thenReturn("破防");
    when(database.optionalOne(anyString(), any(Object[].class)))
        .thenReturn(
            Map.of(
                "id",
                1L,
                "status",
                "editing",
                "processing_note",
                "{\"origin\":\"AI 起源\",\"origin_references\":[{\"title\":\"来源\",\"url\":\"https://example.com/article\"}]}"));
    when(database.update(anyString(), any(Object[].class))).thenReturn(1);
    service =
        new CandidateService(
            database,
            mapper,
            actor,
            normalizer,
            mock(ChangeSetService.class),
            mock(AiVariantGenerator.class));

    service.update(1L, "破防", "情绪受到触动", "other", "人工改写的起源", List.of(), false, false, null);

    ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
    verify(database).update(anyString(), arguments.capture());
    JsonNode note = mapper.readTree(String.valueOf(arguments.getValue()[5]));
    assertThat(note.path("origin").asText()).isEqualTo("人工改写的起源");
    assertThat(note.has("origin_references")).isFalse();
  }

  @Test
  void requiresCommentWhenReturningCandidate() {
    assertThatThrownBy(() -> service.returnForEditing(1L, " "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("审核意见");
    verifyNoInteractions(database);
  }

  @Test
  void rejectsEmptyBatchSubmission() {
    assertThatThrownBy(() -> service.batchSubmit(List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("请选择");
    verifyNoInteractions(database);
  }

  @Test
  void limitsBatchSubmissionToOneHundredCandidates() {
    List<Long> ids = java.util.stream.LongStream.rangeClosed(1, 101).boxed().toList();

    assertThatThrownBy(() -> service.batchSubmit(ids))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("100");
    verifyNoInteractions(database);
  }

  @Test
  void requiresCommentForBatchReturn() {
    assertThatThrownBy(() -> service.batchReturn(List.of(1L, 2L), " "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("审核意见");
    verifyNoInteractions(database);
  }

  @Test
  void createsEditingCandidateWithMappedSourceMetadata() throws Exception {
    TermNormalizer normalizer = mock(TermNormalizer.class);
    when(normalizer.normalize("新词", "zh-CN")).thenReturn("新词");
    when(database.optionalOne(argThat(sql -> sql.contains("UNION ALL")), any(Object[].class)))
        .thenReturn(null);
    when(database.insert(anyString(), any(Object[].class))).thenReturn(42L);
    service =
        new CandidateService(
            database,
            new ObjectMapper(),
            actor,
            normalizer,
            mock(ChangeSetService.class),
            mock(AiVariantGenerator.class));

    var result =
        service.createFromCrawler(
            "popcidian",
            "波普词典",
            new CrawledEntry(
                "新词",
                "新的释义",
                List.of("新词的使用例句"),
                "slang",
                "互联网黑话",
                List.of("互联网", "职场"),
                "https://example.test/new",
                "新词",
                "v1",
                "起源说明",
                List.of(new OriginReference("起源页面", "https://example.test/origin")),
                true,
                List.of("来源信息不足"),
                "search",
                "model",
                "popcidian-ai-enrichment-v1",
                new java.math.BigDecimal("0.7")),
            "system");

    assertThat(result.status()).isEqualTo("imported");
    assertThat(result.candidateId()).isEqualTo(42L);
    verify(database)
        .update(
            argThat(sql -> sql.contains("INSERT IGNORE INTO candidate_admission_locks")),
            any(Object[].class));
    verify(database).optionalOne(argThat(sql -> sql.contains("FOR UPDATE")), any(Object[].class));
    ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
    verify(database).insert(anyString(), arguments.capture());
    assertThat(arguments.getValue()[6]).isEqualTo("system");
    verifyNoInteractions(actor);
    JsonNode note = new ObjectMapper().readTree(String.valueOf(arguments.getValue()[7]));
    assertThat(note.path("category").asText()).isEqualTo("slang");
    assertThat(note.path("source_category").asText()).isEqualTo("互联网黑话");
    assertThat(note.path("source_name").asText()).isEqualTo("波普词典");
    assertThat(note.path("source_tags")).extracting(JsonNode::asText).containsExactly("互联网", "职场");
    assertThat(note.path("examples")).extracting(JsonNode::asText).containsExactly("新词的使用例句");
    assertThat(note.path("origin").asText()).isEqualTo("起源说明");
    assertThat(note.path("origin_references").get(0).path("url").asText())
        .isEqualTo("https://example.test/origin");
  }

  @Test
  void publishesDiscoveryAndOriginEvidenceWhenTheyUseTheSameUrl() {
    ObjectMapper mapper = new ObjectMapper();
    TermNormalizer normalizer = mock(TermNormalizer.class);
    ChangeSetService publishing = mock(ChangeSetService.class);
    AiVariantGenerator variants = mock(AiVariantGenerator.class);
    when(normalizer.normalize("尊嘟假嘟", "zh-CN")).thenReturn("尊嘟假嘟");
    when(database.optionalOne(anyString(), any(Object[].class)))
        .thenReturn(
            Map.of(
                "id", 1L,
                "status", "pending_review",
                "term_raw", "尊嘟假嘟",
                "normalized_term", "尊嘟假嘟",
                "definition_raw", "表示真的假的",
                "source_url", "https://regengbaike.com/1.html",
                "source_type", "crawler",
                "source_name", "热梗百科",
                "submitted_by", "system",
                "processing_note",
                    "{\"origin\":\"源于网络谐音表达\",\"origin_references\":[{\"title\":\"热梗百科：尊嘟假嘟\",\"url\":\"https://regengbaike.com/1.html\"}]}"));
    when(publishing.publishCandidate(
            isNull(), isNull(), any(JsonNode.class), anyString(), eq("system"), eq(false)))
        .thenReturn(Map.of("id", 99L));
    when(actor.currentActor()).thenReturn("reviewer");
    service = new CandidateService(database, mapper, actor, normalizer, publishing, variants);

    service.approve(1L, "同意发布");

    ArgumentCaptor<JsonNode> document = ArgumentCaptor.forClass(JsonNode.class);
    verify(publishing)
        .publishCandidate(
            isNull(), isNull(), document.capture(), anyString(), eq("system"), eq(false));
    assertThat(document.getValue().path("evidence"))
        .extracting(item -> item.path("evidence_role").asString())
        .containsExactly("discovery", "origin");
    assertThat(document.getValue().path("evidence"))
        .extracting(item -> item.path("source_url").asString())
        .containsExactly(
            "https://regengbaike.com/1.html", "https://regengbaike.com/1.html");
  }

  @Test
  void crawlerTermMatchingPublishedEntryIsDuplicate() {
    TermNormalizer normalizer = mock(TermNormalizer.class);
    when(normalizer.normalize("旧词", "zh-CN")).thenReturn("旧词");
    when(database.optionalOne(argThat(sql -> sql.contains("UNION ALL")), any(Object[].class)))
        .thenReturn(Map.of("target_type", "meme", "target_id", 7L));
    service =
        new CandidateService(
            database,
            new ObjectMapper(),
            actor,
            normalizer,
            mock(ChangeSetService.class),
            mock(AiVariantGenerator.class));

    var result =
        service.createFromCrawler(
            "popcidian",
            "波普词典",
            new CrawledEntry(
                "旧词",
                "已有释义",
                List.of(),
                "other",
                null,
                List.of(),
                "https://example.test/old",
                "旧词",
                "v1"),
            "system");

    assertThat(result.status()).isEqualTo("duplicate");
    assertThat(result.duplicateTargetType()).isEqualTo("meme");
    assertThat(result.duplicateTargetId()).isEqualTo(7L);
    verify(database, never()).insert(anyString(), any(Object[].class));
  }

  @Test
  void crawlerPrecheckUsesTheSameDuplicateRulesBeforeAiProcessing() {
    TermNormalizer normalizer = mock(TermNormalizer.class);
    when(normalizer.normalize("旧词", "zh-CN")).thenReturn("旧词");
    when(database.optionalOne(argThat(sql -> sql.contains("UNION ALL")), any(Object[].class)))
        .thenReturn(Map.of("target_type", "meme", "target_id", 7L));
    service =
        new CandidateService(
            database,
            new ObjectMapper(),
            actor,
            normalizer,
            mock(ChangeSetService.class),
            mock(AiVariantGenerator.class));

    var result = service.precheckCrawlerDuplicate("旧词");

    assertThat(result.status()).isEqualTo("duplicate");
    assertThat(result.duplicateTargetType()).isEqualTo("meme");
    assertThat(result.duplicateTargetId()).isEqualTo(7L);
  }

  @Test
  void crawlerTermMatchingActiveVariantIsDuplicate() {
    TermNormalizer normalizer = mock(TermNormalizer.class);
    when(normalizer.normalize("别名", "zh-CN")).thenReturn("别名");
    when(database.optionalOne(argThat(sql -> sql.contains("UNION ALL")), any(Object[].class)))
        .thenReturn(Map.of("target_type", "variant", "target_id", 9L));
    service =
        new CandidateService(
            database,
            new ObjectMapper(),
            actor,
            normalizer,
            mock(ChangeSetService.class),
            mock(AiVariantGenerator.class));

    var result =
        service.createFromCrawler(
            "popcidian",
            "波普词典",
            new CrawledEntry(
                "别名",
                "释义",
                List.of(),
                "other",
                null,
                List.of(),
                "https://example.test/alias",
                "别名",
                "v1"),
            "system");

    assertThat(result.status()).isEqualTo("duplicate");
    assertThat(result.duplicateTargetType()).isEqualTo("variant");
    assertThat(result.duplicateTargetId()).isEqualTo(9L);
    verify(database, never()).insert(anyString(), any(Object[].class));
  }

  @Test
  void crawlerTermMatchingCandidateIsDuplicateRegardlessOfCandidateStatus() {
    TermNormalizer normalizer = mock(TermNormalizer.class);
    when(normalizer.normalize("候选词", "zh-CN")).thenReturn("候选词");
    when(database.optionalOne(argThat(sql -> sql.contains("UNION ALL")), any(Object[].class)))
        .thenReturn(Map.of("target_type", "candidate", "target_id", 11L));
    service =
        new CandidateService(
            database,
            new ObjectMapper(),
            actor,
            normalizer,
            mock(ChangeSetService.class),
            mock(AiVariantGenerator.class));

    var result =
        service.createFromCrawler(
            "popcidian",
            "波普词典",
            new CrawledEntry(
                "候选词",
                "释义",
                List.of(),
                "other",
                null,
                List.of(),
                "https://example.test/candidate",
                "候选词",
                "v1"),
            "system");

    assertThat(result.status()).isEqualTo("duplicate");
    assertThat(result.duplicateTargetType()).isEqualTo("candidate");
    assertThat(result.duplicateTargetId()).isEqualTo(11L);
    verify(database, never()).insert(anyString(), any(Object[].class));
  }

  @Test
  void rejectsEmptyBatchApproval() {
    assertThatThrownBy(() -> service.batchApprove(List.of(), "同意发布"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("请选择");
    verifyNoInteractions(database);
  }
}
