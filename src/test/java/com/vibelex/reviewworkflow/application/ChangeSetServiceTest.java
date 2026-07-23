package com.vibelex.reviewworkflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.vibelex.actorcontext.CurrentActorProvider;
import com.vibelex.candidatediscovery.domain.TermNormalizer;
import com.vibelex.lexicon.application.LexiconSnapshotService;
import com.vibelex.recognition.application.RecognitionIndex;
import com.vibelex.shared.persistence.MyBatisDatabase;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ChangeSetServiceTest {

  private MyBatisDatabase database;
  private ChangeSetService service;

  @BeforeEach
  void setUp() {
    database = mock(MyBatisDatabase.class);
    service =
        new ChangeSetService(
            database,
            mock(ObjectMapper.class),
            mock(CurrentActorProvider.class),
            mock(TermNormalizer.class),
            mock(LexiconSnapshotService.class),
            mock(RecognitionIndex.class));
  }

  @Test
  void returnsPagedChangeSetsAndMetadata() {
    when(database.scalar(anyString(), any(Object[].class))).thenReturn(41L);
    when(database.list(anyString(), any(Object[].class)))
        .thenReturn(List.of(Map.of("id", 2L, "status", "pending_review")));

    Map<String, Object> result = service.list("pending_review", "editor", 2, 20);

    assertThat(result.get("page")).isEqualTo(2);
    assertThat(result.get("size")).isEqualTo(20);
    assertThat(result.get("totalElements")).isEqualTo(41L);
    assertThat(result.get("totalPages")).isEqualTo(3L);
    assertThat((List<?>) result.get("items")).hasSize(1);
  }

  @Test
  void rejectsUnsupportedStatusBeforeQueryingDatabase() {
    assertThatThrownBy(() -> service.list("deleted", "", 1, 20))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不支持的审核状态");
    verifyNoInteractions(database);
  }

  @Test
  void requiresCommentWhenRejecting() {
    assertThatThrownBy(() -> service.transition(1L, "reject", " "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("必须填写审核意见");
    verifyNoInteractions(database);
  }
}
