package com.vibelex.candidatediscovery.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TermNormalizerTest {
  private final TermNormalizer normalizer = new TermNormalizer();

  @Test
  void appliesDocumentedChineseCases() {
    assertThat(normalizer.normalize("  YYDS  ", "zh-CN")).isEqualTo("yyds");
    assertThat(normalizer.normalize("ｔｒｅｅｔｒｅｅ的", "zh-CN")).isEqualTo("treetree的");
    assertThat(normalizer.normalize("破 防", "zh-CN", NormalizationProfile.SPACING)).isEqualTo("破防");
  }

  @Test
  void normalizesPinyinToneAndSeparators() {
    assertThat(normalizer.normalize("pò fáng", "mixed", NormalizationProfile.PINYIN))
        .isEqualTo("pofang");
  }
}
