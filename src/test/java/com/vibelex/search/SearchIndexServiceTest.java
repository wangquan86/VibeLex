package com.vibelex.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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

class SearchIndexServiceTest {
  private MyBatisDatabase database;
  private ElasticsearchGateway es;
  private EmbeddingProvider embedding;
  private SearchIndexService service;

  @BeforeEach
  void setUp() {
    database = mock(MyBatisDatabase.class);
    es = mock(ElasticsearchGateway.class);
    embedding = mock(EmbeddingProvider.class);
    SearchProperties properties = new SearchProperties();
    properties.getEmbedding().setEnabled(true);
    when(es.enabled()).thenReturn(true);
    when(es.indexName()).thenReturn("vibelex_sense");
    when(es.indexAlias()).thenReturn("vibelex_sense_current");
    when(es.aliasExists()).thenReturn(true);
    when(es.aliasCompatible()).thenReturn(true);
    when(embedding.embed(anyString())).thenReturn(List.of(.1f));
    service = new SearchIndexService(database, new ObjectMapper(), properties, es, embedding);
  }

  @Test
  void indexesArchivedActiveSenseWithSharedDocumentContract() {
    when(database.optionalOne(anyString(), any(Object[].class)))
        .thenReturn(
            Map.of(
                "id", 101L,
                "meme_code", "MEME_000101",
                "canonical_term", "破防",
                "language_code", "zh-CN",
                "status", "archived",
                "category", "emotion_expression",
                "domain_tags", "[\"情绪表达\"]"));
    when(database.list(anyString(), any(Object[].class)))
        .thenAnswer(
            invocation -> {
              String sql = invocation.getArgument(0);
              if (sql.contains("FROM meme_senses"))
                return List.of(Map.of("id", 201L, "sense_no", 1, "definition", "受到冲击。"));
              if (sql.contains("FROM meme_variants")) return List.of(Map.of("variant", "我破防了"));
              if (sql.contains("FROM meme_examples"))
                return List.of(Map.of("example_text", "看到结局我破防了。"));
              return List.of();
            });

    service.syncMeme(101L);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> document = ArgumentCaptor.forClass(Map.class);
    verify(es).deleteByMeme("vibelex_sense_current", 101L);
    verify(es).upsert(eq("vibelex_sense_current"), eq("meme-101-sense-201"), document.capture());
    @SuppressWarnings("unchecked")
    ArgumentCaptor<String> embeddingText = ArgumentCaptor.forClass(String.class);
    verify(embedding).embed(embeddingText.capture());
    assertThat(document.getValue())
        .containsEntry("entry_status", "archived")
        .containsEntry("sense_id", 201L)
        .containsEntry("examples", List.of("看到结局我破防了。"))
        .doesNotContainKeys(
            "detect_enabled", "generate_enabled", "recommend_enabled", "scenes", "indexed_at");
    assertThat(embeddingText.getValue()).doesNotContain("鐪嬪埌缁撳眬鎴戠牬闃蹭簡銆?");
  }

  @Test
  void doesNotCreateEntryLevelDocumentWithoutActiveSense() {
    when(database.optionalOne(anyString(), any(Object[].class)))
        .thenReturn(
            Map.of(
                "id", 101L,
                "meme_code", "MEME_000101",
                "canonical_term", "破防",
                "language_code", "zh-CN",
                "status", "published",
                "category", "emotion_expression"));
    when(database.list(anyString(), any(Object[].class))).thenReturn(List.of());

    service.syncMeme(101L);

    verify(es).deleteByMeme("vibelex_sense_current", 101L);
    verify(es, never()).upsert(anyString(), anyString(), any());
  }

  @Test
  void doesNotMarkSynchronizationSuccessfulWhenSearchIsDisabled() {
    when(es.enabled()).thenReturn(false);

    assertThatThrownBy(() -> service.syncMeme(101L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("未启用");
    assertThatThrownBy(() -> service.deleteMeme(101L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("未启用");
  }

  @Test
  void refusesToWriteIncrementalDocumentsToLegacyMapping() {
    when(es.aliasCompatible()).thenReturn(false);

    assertThatThrownBy(() -> service.syncMeme(101L))
        .isInstanceOf(SearchIndexNotReadyException.class)
        .hasMessageContaining("全量重建");
    verify(es, never()).deleteByMeme(anyString(), anyLong());
    verify(es, never()).upsert(anyString(), anyString(), any());
  }

  @Test
  void refusesIncrementalSynchronizationBeforeInitialFullRebuild() {
    when(es.aliasExists()).thenReturn(false);

    assertThatThrownBy(() -> service.syncMeme(101L))
        .isInstanceOf(SearchIndexNotReadyException.class)
        .hasMessageContaining("全量重建");
    verify(es, never()).createIndex(anyString());
    verify(es, never()).deleteByMeme(anyString(), anyLong());
    verify(es, never()).upsert(anyString(), anyString(), any());
  }

  @Test
  void switchesAliasThenReportsOldIndexCleanupFailure() {
    when(database.list(anyString(), any(Object[].class)))
        .thenAnswer(
            invocation -> {
              String sql = invocation.getArgument(0);
              if (sql.contains("SELECT id FROM meme_entries")) return List.of();
              return List.of();
            });
    when(es.switchAlias(anyString())).thenReturn(List.of("vibelex_sense_v2_legacy"));
    org.mockito.Mockito.doThrow(new IllegalStateException("delete failed"))
        .when(es)
        .deleteIndex("vibelex_sense_v2_legacy");

    SearchIndexService.RebuildReport report = service.rebuildAll();

    assertThat(report.index()).startsWith("vibelex_sense_");
    assertThat(report.previousIndices()).containsExactly("vibelex_sense_v2_legacy");
    assertThat(report.deletedIndices()).isEmpty();
    assertThat(report.cleanupFailures()).containsExactly("vibelex_sense_v2_legacy");
    verify(es).switchAlias(report.index());
  }
}
