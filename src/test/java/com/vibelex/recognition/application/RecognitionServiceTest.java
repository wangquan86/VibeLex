package com.vibelex.recognition.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.vibelex.candidatediscovery.domain.TermNormalizer;
import com.vibelex.recognition.application.RecognitionIndex.Anchor;
import com.vibelex.recognition.application.RecognitionIndex.Data;
import com.vibelex.recognition.application.RecognitionIndex.Entry;
import com.vibelex.recognition.application.RecognitionIndex.Example;
import com.vibelex.recognition.application.RecognitionIndex.Sense;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class RecognitionServiceTest {

  @Test
  void returnsAtMostThreeApplicableExamplesForResolvedSense() {
    RecognitionService service = serviceWith(senses(), examples());

    Map<String, Object> match = firstMatch(service.recognize(request()));

    assertThat(match.get("definition")).isEqualTo("情绪受到冲击而失去心理防线。");
    assertThat(match.get("examples")).isEqualTo(List.of("通用例句", "义项例句一", "义项例句二"));
  }

  @Test
  void returnsEmptyExamplesWhenSenseIsAmbiguous() {
    List<Sense> ambiguousSenses =
        List.of(new Sense(201L, 1, "情绪义", null), new Sense(202L, 2, "游戏义", null));
    RecognitionService service = serviceWith(ambiguousSenses, examples());

    Map<String, Object> match = firstMatch(service.recognize(request()));

    assertThat(match.get("ambiguous")).isEqualTo(true);
    assertThat(match.get("definition")).isNull();
    assertThat(match.get("examples")).isEqualTo(List.of());
  }

  private RecognitionService serviceWith(List<Sense> senses, List<Example> examples) {
    RecognitionIndex index = mock(RecognitionIndex.class);
    Data data =
        new Data(
            List.of(new Anchor(101L, null, "破防", null, "rule:exact_match")),
            Map.of(
                101L,
                new Entry(
                    101L,
                    "MEME_000101",
                    "破防",
                    "zh-CN",
                    "published",
                    "none",
                    true,
                    true,
                    true,
                    true,
                    "normal")),
            Map.of(101L, senses),
            Map.of(101L, examples),
            Map.of());
    when(index.get()).thenReturn(data);
    return new RecognitionService(index, new TermNormalizer(), new ObjectMapper());
  }

  private List<Sense> senses() {
    return List.of(new Sense(201L, 1, "情绪受到冲击而失去心理防线。", null));
  }

  private List<Example> examples() {
    return List.of(
        new Example(null, "通用例句"),
        new Example(201L, "义项例句一"),
        new Example(202L, "其他义项例句"),
        new Example(201L, "义项例句二"),
        new Example(201L, "义项例句三"));
  }

  private RecognitionService.Request request() {
    return new RecognitionService.Request("今天真的破防了", "zh-CN", null, null);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> firstMatch(Map<String, Object> response) {
    return ((List<Map<String, Object>>) response.get("matches")).get(0);
  }
}
