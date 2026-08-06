package com.vibelex.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.vibelex.recommendation.api.RecommendationModels;
import com.vibelex.search.ElasticsearchGateway;
import com.vibelex.search.ElasticsearchGateway.Hit;
import com.vibelex.search.EmbeddingProvider;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RecommendationServiceTest {
  private RecommendationProperties properties;
  private ElasticsearchGateway es;
  private EmbeddingProvider embedding;
  private RerankerClient reranker;
  private RecommendationOriginQuery originQuery;
  private RecommendationService service;

  @BeforeEach
  void setUp() {
    properties = new RecommendationProperties();
    properties.setEnabled(true);
    es = mock(ElasticsearchGateway.class);
    embedding = mock(EmbeddingProvider.class);
    reranker = mock(RerankerClient.class);
    originQuery = mock(RecommendationOriginQuery.class);
    when(reranker.enabled()).thenReturn(false);
    when(es.enabled()).thenReturn(true);
    when(es.indexAlias()).thenReturn("vibelex_sense_current");
    when(embedding.embed(anyString())).thenReturn(List.of(.1f));
    when(es.knnForRecommendation(anyList(), org.mockito.ArgumentMatchers.anyInt()))
        .thenReturn(List.of());
    when(es.lexicalForRecommendation(anyString(), org.mockito.ArgumentMatchers.anyInt()))
        .thenReturn(List.of());
    when(originQuery.find(anyList())).thenReturn(Map.of());
    service = new RecommendationService(properties, es, embedding, reranker, originQuery);
  }

  @Test
  void includesOriginsByDefault() {
    when(es.knnForRecommendation(anyList(), org.mockito.ArgumentMatchers.anyInt()))
        .thenReturn(List.of(hit(101, 201, 1, .9)));
    RecommendationModels.Origin origin =
        new RecommendationModels.Origin("起源说明", List.of());
    when(originQuery.find(anyList())).thenReturn(Map.of(101L, origin));

    RecommendationModels.Response response =
        service.recommend(new RecommendationModels.Request("有效上下文", null, null));

    assertThat(properties.getOrigin().isEnabled()).isTrue();
    assertThat(response.recommendations().get(0).origin()).isEqualTo(origin);
  }

  @Test
  void skipsOriginQueryWhenOriginResponseIsDisabled() {
    properties.getOrigin().setEnabled(false);
    when(es.knnForRecommendation(anyList(), org.mockito.ArgumentMatchers.anyInt()))
        .thenReturn(List.of(hit(101, 201, 1, .9)));

    RecommendationModels.Response response =
        service.recommend(new RecommendationModels.Request("有效上下文", null, null));

    assertThat(response.recommendations().get(0).origin()).isNull();
    verifyNoInteractions(originQuery);
  }

  @Test
  void retainsRecommendationsWhenOriginQueryFails() {
    when(es.knnForRecommendation(anyList(), org.mockito.ArgumentMatchers.anyInt()))
        .thenReturn(List.of(hit(101, 201, 1, .9)));
    doThrow(new IllegalStateException("database unavailable")).when(originQuery).find(anyList());

    RecommendationModels.Response response =
        service.recommend(new RecommendationModels.Request("有效上下文", null, null));

    assertThat(response.recommendations()).hasSize(1);
    assertThat(response.recommendations().get(0).origin()).isNull();
  }

  @Test
  void fusesBothPathsAndKeepsHighestRankedSensePerMeme() {
    Hit firstSense = hit(101, 201, 1, .91);
    Hit secondSense = hit(101, 202, 2, .88);
    Hit otherMeme = hit(102, 203, 1, .80);
    when(es.knnForRecommendation(anyList(), org.mockito.ArgumentMatchers.anyInt()))
        .thenReturn(List.of(firstSense, secondSense, otherMeme));
    when(es.lexicalForRecommendation(anyString(), org.mockito.ArgumentMatchers.anyInt()))
        .thenReturn(List.of(secondSense, otherMeme));

    RecommendationModels.Response response =
        service.recommend(new RecommendationModels.Request("努力很久仍然失败", null, null));

    assertThat(response.engineVersion()).isEqualTo("3.2");
    assertThat(response.indexVersion()).isEqualTo("vibelex_sense_current");
    assertThat(response.recommendations())
        .extracting(RecommendationModels.Item::memeId)
        .containsExactly(101L, 102L);
    assertThat(response.recommendations().get(0).senseId()).isEqualTo(202L);
    assertThat(response.recommendations().get(0).relevanceScore())
        .isBetween(BigDecimal.ZERO, BigDecimal.ONE);
  }

  @Test
  void returnsSemanticResultsWhenLexicalRecallFails() {
    when(es.knnForRecommendation(anyList(), org.mockito.ArgumentMatchers.anyInt()))
        .thenReturn(List.of(hit(101, 201, 1, .9)));
    when(es.lexicalForRecommendation(anyString(), org.mockito.ArgumentMatchers.anyInt()))
        .thenThrow(new IllegalStateException("lexical unavailable"));

    RecommendationModels.Response response =
        service.recommend(new RecommendationModels.Request("  文本中没有候选词条原文  ", "zh-CN", 5));

    assertThat(response.recommendations()).hasSize(1);
    verify(embedding).embed("文本中没有候选词条原文");
  }

  @Test
  void rerankerReordersRequestedCandidatesAndPublishesItsScore() {
    when(reranker.enabled()).thenReturn(true);
    when(es.knnForRecommendation(anyList(), org.mockito.ArgumentMatchers.anyInt()))
        .thenReturn(List.of(hit(101, 201, 1, .95), hit(102, 202, 1, .90), hit(103, 203, 1, .85)));
    when(reranker.rerank(anyString(), anyList())).thenReturn(List.of(.10, .90));

    RecommendationModels.Response response =
        service.recommend(new RecommendationModels.Request("连续尝试后失败", "zh-CN", 2));

    assertThat(response.recommendations())
        .extracting(RecommendationModels.Item::memeId)
        .containsExactly(102L, 101L);
    assertThat(response.recommendations().get(0).relevanceScore())
        .isEqualByComparingTo("0.900000");
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<String>> texts = ArgumentCaptor.forClass(List.class);
    verify(reranker).rerank(org.mockito.ArgumentMatchers.eq("连续尝试后失败"), texts.capture());
    assertThat(texts.getValue()).hasSize(2);
    assertThat(texts.getValue().get(0)).contains("词条：", "释义：");
    assertThat(texts.getValue().get(0)).doesNotContain("典型用法：", "例句一", "例句二");
  }

  @Test
  void rerankerTiesRetainRrfOrder() {
    when(reranker.enabled()).thenReturn(true);
    when(es.knnForRecommendation(anyList(), org.mockito.ArgumentMatchers.anyInt()))
        .thenReturn(List.of(hit(101, 201, 1, .95), hit(102, 202, 1, .90)));
    when(reranker.rerank(anyString(), anyList())).thenReturn(List.of(.5, .5));

    RecommendationModels.Response response =
        service.recommend(new RecommendationModels.Request("有效上下文", "zh-CN", 2));

    assertThat(response.recommendations())
        .extracting(RecommendationModels.Item::memeId)
        .containsExactly(101L, 102L);
  }

  @Test
  void rerankerFailureFallsBackToRrfOrder() {
    when(reranker.enabled()).thenReturn(true);
    when(es.knnForRecommendation(anyList(), org.mockito.ArgumentMatchers.anyInt()))
        .thenReturn(List.of(hit(101, 201, 1, .95), hit(102, 202, 1, .90)));
    doThrow(new IllegalStateException("unavailable"))
        .when(reranker)
        .rerank(anyString(), anyList());

    RecommendationModels.Response response =
        service.recommend(new RecommendationModels.Request("有效上下文", "zh-CN", 2));

    assertThat(response.recommendations())
        .extracting(RecommendationModels.Item::memeId)
        .containsExactly(101L, 102L);
  }

  @Test
  void reportsSemanticDependencyFailureAsUnavailable() {
    when(embedding.embed(anyString())).thenThrow(new IllegalStateException("down"));

    assertThatThrownBy(
            () -> service.recommend(new RecommendationModels.Request("有效上下文", null, null)))
        .isInstanceOf(RecommendationUnavailableException.class)
        .hasMessage("推荐语义服务不可用");
  }

  @Test
  void validatesUnicodeCodePointsLanguageAndResultLimit() {
    assertThatThrownBy(
            () ->
                service.recommend(new RecommendationModels.Request("😀".repeat(481), "zh-CN", 10)))
        .isInstanceOf(RecommendationContextTooLongException.class);
    assertThatThrownBy(
            () -> service.recommend(new RecommendationModels.Request("有效上下文", "en-US", 10)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("zh-CN");
    assertThatThrownBy(() -> service.recommend(new RecommendationModels.Request("有效上下文", null, 21)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("max_results");
  }

  private Hit hit(long memeId, long senseId, int senseNo, double score) {
    return new Hit(
        memeId,
        senseId,
        senseNo,
        "MEME_" + memeId,
        "破防" + memeId,
        List.of("我破防了"),
        "zh-CN",
        "published",
        "emotion_expression",
        List.of("情绪表达"),
        "情绪受到冲击。",
        List.of("例句一", "例句二"),
        score);
  }
}
