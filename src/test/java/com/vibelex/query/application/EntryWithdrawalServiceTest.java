package com.vibelex.query.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vibelex.actorcontext.CurrentActorProvider;
import com.vibelex.reviewworkflow.application.ChangeSetService;
import com.vibelex.shared.persistence.MyBatisDatabase;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class EntryWithdrawalServiceTest {

  @Test
  void reusesOriginalCandidateAndRemovesArchivedEntryFromIndexes() {
    MyBatisDatabase database = mock(MyBatisDatabase.class);
    EntryAdminQueryService entries = mock(EntryAdminQueryService.class);
    CurrentActorProvider actor = mock(CurrentActorProvider.class);
    ChangeSetService publishing = mock(ChangeSetService.class);
    ObjectMapper mapper = new ObjectMapper();
    EntryWithdrawalService service =
        new EntryWithdrawalService(database, entries, mapper, actor, publishing);

    ObjectNode snapshot = mapper.createObjectNode();
    snapshot
        .putObject("meme_entry")
        .put("id", 7L)
        .put("meme_code", "MEME_000007")
        .put("canonical_term", "破防")
        .put("normalized_term", "破防")
        .put("category", "emotion_expression")
        .put("status", "published")
        .put("current_version", 1);
    snapshot.putArray("senses").addObject().put("definition", "情绪受到冲击。");
    snapshot.putArray("examples").addObject().put("example_text", "看到结局我破防了。");
    snapshot.putArray("variants");

    when(database.one(anyString(), any(Object[].class)))
        .thenReturn(Map.of("id", 7L, "status", "published", "current_version", 1));
    when(entries.detail(7L)).thenReturn(Map.of("snapshot", snapshot));
    when(database.optionalOne(anyString(), any(Object[].class)))
        .thenReturn(
            Map.of(
                "id", 11L,
                "status", "published",
                "published_meme_id", 7L,
                "source_type", "import",
                "import_run_id", 3L,
                "processing_note", "{}"));
    when(database.update(anyString(), any(Object[].class))).thenReturn(1);
    when(database.insert(anyString(), any(Object[].class))).thenReturn(2L);
    when(actor.currentActor()).thenReturn("editor01");

    Map<String, Object> result = service.withdraw(7L, "补充释义");

    assertThat(result).containsEntry("entryId", 7L).containsEntry("candidateId", 11L);
    assertThat(result).doesNotContainKey("status");
    verify(database)
        .update(
            argThat(
                sql ->
                    sql.contains("UPDATE candidate_entries")
                        && !sql.contains("source_type")
                        && !sql.contains("import_run_id")),
            any(Object[].class));
    verify(publishing).removeRecognitionIndex(7L);

    ArgumentCaptor<Object[]> revisionArguments = ArgumentCaptor.forClass(Object[].class);
    verify(database)
        .insert(argThat(sql -> sql.contains("INSERT INTO meme_revisions")), revisionArguments.capture());
    assertThat(revisionArguments.getValue()[0]).isEqualTo(7L);
    assertThat(revisionArguments.getValue()[1]).isEqualTo(2);
    assertThat(String.valueOf(revisionArguments.getValue()[2])).contains("撤回至候选池");
  }
}
