package com.vibelex.recommendation.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class RecommendationModelsTest {
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void omitsOriginWhenServerSideEnrichmentIsDisabled() throws Exception {
    String json = mapper.writeValueAsString(item(null));

    assertThat(json).doesNotContain("\"origin\"");
  }

  @Test
  void includesEmptyEvidenceWhenOriginEnrichmentFindsNoData() throws Exception {
    String json =
        mapper.writeValueAsString(new RecommendationModels.Origin(null, List.of()));

    assertThat(json).doesNotContain("summary").contains("\"evidence\":[]");
  }

  private RecommendationModels.Item item(RecommendationModels.Origin origin) {
    return new RecommendationModels.Item(
        101L,
        "MEME_000101",
        "词条",
        List.of(),
        201L,
        1,
        "释义",
        List.of(),
        "other",
        List.of(),
        origin,
        BigDecimal.ONE);
  }
}
