package com.vibelex.recognition.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.vibelex.candidatediscovery.domain.NormalizationProfile;
import com.vibelex.candidatediscovery.domain.TermNormalizer;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

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

  @Test
  void anchorsSpacedInputUsingUnspacedEsTerm() {
    RecognitionService service = new RecognitionService(null, normalizer, new ObjectMapper());

    List<RecognitionService.Candidate> candidates =
        service.anchorCandidates(
            "这薯片吃起来特别 treetree 的，真的破防了。", "zh-CN", 1817, 1859L, List.of("treetree的"), "lexical");

    assertThat(candidates)
        .containsExactly(new RecognitionService.Candidate(1817, 1859L, 9, 19, "lexical"));
  }
}
