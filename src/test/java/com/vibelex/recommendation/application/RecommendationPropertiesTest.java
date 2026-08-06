package com.vibelex.recommendation.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RecommendationPropertiesTest {
  @Test
  void rejectsInvalidWeightsAndTopK() {
    RecommendationProperties invalidWeights = new RecommendationProperties();
    invalidWeights.setSemanticWeight(.8);
    assertThatThrownBy(invalidWeights::validate)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("权重");

    RecommendationProperties invalidTopK = new RecommendationProperties();
    invalidTopK.setSemanticTopK(10);
    assertThatThrownBy(invalidTopK::validate)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Top K");
  }

  @Test
  void rerankerIsEnabledByDefaultAndRejectsInvalidConfiguration() {
    RecommendationProperties defaults = new RecommendationProperties();
    org.assertj.core.api.Assertions.assertThat(defaults.getReranker().isEnabled()).isTrue();

    RecommendationProperties invalid = new RecommendationProperties();
    invalid.getReranker().setEndpoint("not-an-http-url");
    assertThatThrownBy(invalid::validate)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("endpoint");
  }

  @Test
  void originResponseIsEnabledAndBoundedByDefault() {
    RecommendationProperties defaults = new RecommendationProperties();

    org.assertj.core.api.Assertions.assertThat(defaults.getOrigin().isEnabled()).isTrue();
    org.assertj.core.api.Assertions.assertThat(defaults.getOrigin().getMaxEvidencePerItem())
        .isEqualTo(10);

    defaults.getOrigin().setMaxEvidencePerItem(0);
    assertThatThrownBy(defaults::validate)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("origin evidence limit");
  }
}
