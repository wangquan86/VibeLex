package com.vibelex.candidatediscovery.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.vibelex.actorcontext.CurrentActorProvider;
import com.vibelex.candidatediscovery.domain.TermNormalizer;
import com.vibelex.llm.AiVariantGenerator;
import com.vibelex.reviewworkflow.application.ChangeSetService;
import com.vibelex.shared.persistence.MyBatisDatabase;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class CandidateServiceTest {

  private MyBatisDatabase database;
  private CandidateService service;
  private CurrentActorProvider actor;

  @BeforeEach
  void setUp() {
    database = mock(MyBatisDatabase.class);
    actor = mock(CurrentActorProvider.class);
    service =
        new CandidateService(
            database,
            mock(ObjectMapper.class),
            actor,
            mock(TermNormalizer.class),
            mock(ChangeSetService.class),
            mock(AiVariantGenerator.class));
  }

  @Test
  void returnsPagedCandidatesAndMetadata() {
    when(database.scalar(anyString(), any(Object[].class))).thenReturn(45L);
    when(database.list(anyString(), any(Object[].class)))
        .thenReturn(List.of(Map.of("id", 21L, "status", "editing")));

    Map<String, Object> result = service.list("editing", 2, 20, "测试", "CHIME");

    assertThat(result.get("page")).isEqualTo(2);
    assertThat(result.get("size")).isEqualTo(20);
    assertThat(result.get("totalElements")).isEqualTo(45L);
    assertThat(result.get("totalPages")).isEqualTo(3L);
    assertThat((List<?>) result.get("items")).hasSize(1);
  }

  @Test
  void limitsPageSizeToOneHundred() {
    when(database.scalar(anyString(), any(Object[].class))).thenReturn(0L);
    when(database.list(anyString(), any(Object[].class))).thenReturn(List.of());

    Map<String, Object> result = service.list("all", 1, 1000, "", "");

    assertThat(result.get("size")).isEqualTo(100);
  }

  @Test
  void rejectsUnsupportedStatusBeforeQueryingDatabase() {
    assertThatThrownBy(() -> service.list("deleted", 1, 20, "", ""))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不支持的候选状态");
    verifyNoInteractions(database);
  }

  @Test
  void doesNotAllowEditingCandidateUnderReview() {
    when(database.optionalOne(anyString(), any(Object[].class)))
        .thenReturn(
            Map.of(
                "id", 1L,
                "status", "pending_review",
                "processing_note", "{}"));

    assertThatThrownBy(
            () -> service.update(1L, "词条", "释义", "other", "", List.of(), false, false, ""))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("编辑中或已退回");
  }

  @Test
  void requiresCommentWhenReturningCandidate() {
    assertThatThrownBy(() -> service.returnForEditing(1L, " "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("审核意见");
    verifyNoInteractions(database);
  }

  @Test
  void rejectsEmptyBatchSubmission() {
    assertThatThrownBy(() -> service.batchSubmit(List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("请选择");
    verifyNoInteractions(database);
  }

  @Test
  void limitsBatchSubmissionToOneHundredCandidates() {
    List<Long> ids = java.util.stream.LongStream.rangeClosed(1, 101).boxed().toList();

    assertThatThrownBy(() -> service.batchSubmit(ids))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("100");
    verifyNoInteractions(database);
  }

  @Test
  void requiresCommentForBatchReturn() {
    assertThatThrownBy(() -> service.batchReturn(List.of(1L, 2L), " "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("审核意见");
    verifyNoInteractions(database);
  }

  @Test
  void rejectsEmptyBatchApproval() {
    assertThatThrownBy(() -> service.batchApprove(List.of(), "同意发布"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("请选择");
    verifyNoInteractions(database);
  }
}
