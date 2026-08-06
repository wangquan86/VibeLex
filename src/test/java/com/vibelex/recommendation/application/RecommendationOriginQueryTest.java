package com.vibelex.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.vibelex.recommendation.api.RecommendationModels;
import com.vibelex.shared.persistence.MyBatisDatabase;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RecommendationOriginQueryTest {
  @Test
  @SuppressWarnings("unchecked")
  void loadsEntryAndSelectedSenseOriginEvidenceInTwoBatchQueries() {
    MyBatisDatabase database = mock(MyBatisDatabase.class);
    RecommendationProperties properties = new RecommendationProperties();
    RecommendationOriginQuery query = new RecommendationOriginQuery(database, properties);
    when(database.list(anyString(), any(Object[].class)))
        .thenReturn(
            List.of(
                row("meme_id", 101L, "origin_summary", "起源说明"),
                row("meme_id", 102L, "origin_summary", null)),
            List.of(
                evidence(101L, null, "词条级证据", "0.8000"),
                evidence(101L, 201L, "当前义项证据", "0.9000"),
                evidence(101L, 999L, "其他义项证据", "0.7000")));

    Map<Long, RecommendationModels.Origin> origins =
        query.find(List.of(item(101L, 201L), item(102L, 202L)));

    assertThat(origins).containsOnlyKeys(101L, 102L);
    assertThat(origins.get(101L).summary()).isEqualTo("起源说明");
    assertThat(origins.get(101L).evidence())
        .extracting(RecommendationModels.OriginEvidence::note)
        .containsExactly("词条级证据", "当前义项证据");
    assertThat(origins.get(101L).evidence().get(0).observedAt())
        .isEqualTo(Instant.parse("2026-08-05T12:30:00Z"));
    assertThat(origins.get(101L).evidence().get(0).confidence())
        .isEqualByComparingTo("0.8000");
    assertThat(origins.get(102L).summary()).isNull();
    assertThat(origins.get(102L).evidence()).isEmpty();
  }

  @Test
  void doesNotQueryDatabaseForEmptyRecommendations() {
    MyBatisDatabase database = mock(MyBatisDatabase.class);
    RecommendationOriginQuery query =
        new RecommendationOriginQuery(database, new RecommendationProperties());

    assertThat(query.find(List.of())).isEmpty();
    verifyNoInteractions(database);
  }

  private RecommendationModels.Item item(long memeId, long senseId) {
    return new RecommendationModels.Item(
        memeId,
        "MEME_" + memeId,
        "词条" + memeId,
        List.of(),
        senseId,
        1,
        "释义",
        List.of(),
        "other",
        List.of(),
        null,
        BigDecimal.ONE);
  }

  private Map<String, Object> evidence(
      long memeId, Long senseId, String note, String confidence) {
    return row(
        "meme_id",
        memeId,
        "sense_id",
        senseId,
        "source_name",
        "来源",
        "source_url",
        "https://example.com/origin",
        "source_layer",
        "dictionary",
        "evidence_note",
        note,
        "observed_at",
        Timestamp.valueOf("2026-08-05 12:30:00"),
        "confidence",
        new BigDecimal(confidence));
  }

  private Map<String, Object> row(Object... values) {
    Map<String, Object> row = new LinkedHashMap<>();
    for (int index = 0; index < values.length; index += 2)
      row.put(String.valueOf(values[index]), values[index + 1]);
    return row;
  }
}
