package com.vibelex.recognition.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.vibelex.candidatediscovery.domain.NormalizationProfile;
import com.vibelex.candidatediscovery.domain.TermNormalizer;
import org.junit.jupiter.api.Test;

class NormalizedViewTest {
  private final TermNormalizer normalizer = new TermNormalizer();

  @Test
  void mapsSpacingMatchBackToOriginalCodePointSpan() {
    NormalizedView view =
        NormalizedView.of("😀po fang!", "mixed", NormalizationProfile.PINYIN, normalizer);
    int start = view.text.indexOf("pofang");
    NormalizedView.Span span = view.span(start, start + 6);
    assertThat(span.start()).isEqualTo(1);
    assertThat(span.end()).isEqualTo(8);
  }
}
