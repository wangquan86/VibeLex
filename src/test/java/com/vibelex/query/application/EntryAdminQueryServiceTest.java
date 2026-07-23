package com.vibelex.query.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.vibelex.lexicon.application.LexiconSnapshotService;
import com.vibelex.shared.persistence.MyBatisDatabase;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EntryAdminQueryServiceTest {

  private MyBatisDatabase database;
  private EntryAdminQueryService service;

  @BeforeEach
  void setUp() {
    database = mock(MyBatisDatabase.class);
    service = new EntryAdminQueryService(database, mock(LexiconSnapshotService.class));
  }

  @Test
  void returnsPagedEntriesAndMetadata() {
    when(database.scalar(anyString(), any(Object[].class))).thenReturn(61L);
    when(database.list(anyString(), any(Object[].class)))
        .thenReturn(List.of(Map.of("id", 1L, "status", "published")));

    Map<String, Object> result = service.list("published", "low", "破防", "CHIME", 2, 20);

    assertThat(result.get("page")).isEqualTo(2);
    assertThat(result.get("size")).isEqualTo(20);
    assertThat(result.get("totalElements")).isEqualTo(61L);
    assertThat(result.get("totalPages")).isEqualTo(4L);
    assertThat((List<?>) result.get("items")).hasSize(1);
  }

  @Test
  void limitsPageSizeToOneHundred() {
    when(database.scalar(anyString(), any(Object[].class))).thenReturn(0L);
    when(database.list(anyString(), any(Object[].class))).thenReturn(List.of());

    Map<String, Object> result = service.list("all", "all", "", "", 1, 1000);

    assertThat(result.get("size")).isEqualTo(100);
  }

  @Test
  void rejectsUnsupportedFiltersBeforeQueryingDatabase() {
    assertThatThrownBy(() -> service.list("deleted", "low", "", "", 1, 20))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不支持的词条状态");
    assertThatThrownBy(() -> service.list("published", "critical", "", "", 1, 20))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不支持的风险等级");
    verifyNoInteractions(database);
  }
}
