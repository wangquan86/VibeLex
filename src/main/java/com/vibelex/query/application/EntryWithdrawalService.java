package com.vibelex.query.application;

import com.vibelex.actorcontext.CurrentActorProvider;
import com.vibelex.reviewworkflow.application.ChangeSetService;
import com.vibelex.shared.persistence.MyBatisDatabase;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Service
public class EntryWithdrawalService {
  private final MyBatisDatabase database;
  private final EntryAdminQueryService entries;
  private final ObjectMapper mapper;
  private final CurrentActorProvider actor;
  private final ChangeSetService publishing;

  public EntryWithdrawalService(
      MyBatisDatabase database,
      EntryAdminQueryService entries,
      ObjectMapper mapper,
      CurrentActorProvider actor,
      ChangeSetService publishing) {
    this.database = database;
    this.entries = entries;
    this.mapper = mapper;
    this.actor = actor;
    this.publishing = publishing;
  }

  @Transactional
  public Map<String, Object> withdraw(long entryId, String reason) {
    Map<String, Object> row =
        database.one("SELECT * FROM meme_entries WHERE id=? FOR UPDATE", entryId);
    if (!"published".equals(row.get("status"))) {
      throw new IllegalStateException("只有已发布词条可以撤回至候选池");
    }

    ObjectNode snapshot = (ObjectNode) entries.detail(entryId).get("snapshot");
    Map<String, Object> candidate = findLifecycleCandidate(entryId);
    if (candidate == null) {
      throw new IllegalStateException("正式词条缺少原候选及来源，无法撤回");
    }
    long candidateId = reactivateCandidate(candidate, snapshot, entryId, reason);

    int nextVersion = ((Number) row.get("current_version")).intValue() + 1;
    ObjectNode archiveSnapshot = snapshot.deepCopy();
    ObjectNode archiveEntry = (ObjectNode) archiveSnapshot.path("meme_entry");
    archiveEntry.put("status", "archived");
    archiveEntry.put("current_version", nextVersion);
    String summary =
        reason == null || reason.isBlank() ? "撤回至候选池" : "撤回至候选池：" + reason.trim();

    int changed =
        database.update(
            "UPDATE meme_entries SET status='archived', current_version=? WHERE id=? AND status='published'",
            nextVersion,
            entryId);
    if (changed == 0) throw new IllegalStateException("词条状态已变化，请刷新后重试");
    database.update("UPDATE meme_safety_policies SET display_enabled=0 WHERE meme_id=?", entryId);
    database.insert(
        """
        INSERT INTO meme_revisions(
            meme_id, version, change_type, change_summary,
            snapshot, changed_by, reviewed_by
        ) VALUES (?, ?, 'archive', ?, ?, ?, ?)
        """,
        entryId,
        nextVersion,
        summary,
        json(archiveSnapshot),
        actor.currentActor(),
        actor.currentActor());

    // Archived entries are not part of any recognition or recommendation surface.
    publishing.removeRecognitionIndex(entryId);
    return Map.of("entryId", entryId, "candidateId", candidateId);
  }

  @Transactional
  public Map<String, Object> withdrawBatch(List<Long> entryIds, String reason) {
    if (entryIds == null || entryIds.isEmpty()) throw new IllegalArgumentException("请选择要撤回的正式词条");
    List<Long> ids = entryIds.stream().filter(id -> id != null).distinct().toList();
    if (ids.isEmpty() || ids.size() > 100)
      throw new IllegalArgumentException("单次撤回数量必须在 1 到 100 条之间");
    List<Long> candidateIds = new ArrayList<>();
    for (Long id : ids) {
      Map<String, Object> result = withdraw(id, reason);
      candidateIds.add(((Number) result.get("candidateId")).longValue());
    }
    return Map.of("withdrawnCount", candidateIds.size(), "candidateIds", candidateIds);
  }

  private Map<String, Object> findLifecycleCandidate(long entryId) {
    return database.optionalOne(
        """
        SELECT *
        FROM candidate_entries
        WHERE published_meme_id=?
        ORDER BY CASE WHEN parser_version='withdraw-v1' THEN 1 ELSE 0 END, id
        LIMIT 1
        FOR UPDATE
        """,
        entryId);
  }

  private long reactivateCandidate(
      Map<String, Object> candidate, ObjectNode snapshot, long entryId, String reason) {
    JsonNode entry = snapshot.path("meme_entry");
    JsonNode sense = snapshot.path("senses").path(0);
    String term = entry.path("canonical_term").asText();
    String definition = sense.path("definition").asText(sense.path("short_definition").asText());
    if (term.isBlank() || definition.isBlank()) throw new IllegalStateException("正式词条快照缺少词形或释义");
    try {
      int changed =
          database.update(
              """
              UPDATE candidate_entries
              SET term_raw=?, normalized_term=?, definition_raw=?, status='editing',
                  duplicate_meme_id=NULL, submitted_by=NULL, submitted_at=NULL,
                  review_base_version=NULL, reviewed_by=NULL, reviewed_at=NULL,
                  review_comment=NULL, processing_note=?
              WHERE id=? AND published_meme_id=? AND status='published'
              """,
              term,
              entry.path("normalized_term").asText(),
              definition,
              candidateNote(candidate, snapshot, entryId, reason),
              candidate.get("id"),
              entryId);
      if (changed == 0) throw new IllegalStateException("候选状态已变化，请刷新后重试");
      return ((Number) candidate.get("id")).longValue();
    } catch (RuntimeException exception) {
      throw exception;
    } catch (Exception exception) {
      throw new IllegalStateException("无法恢复原候选", exception);
    }
  }

  private String candidateNote(
      Map<String, Object> candidate, ObjectNode snapshot, long entryId, String reason) throws Exception {
    ObjectNode note = parseNote((String) candidate.get("processing_note"));
    JsonNode entry = snapshot.path("meme_entry");
    note.put("category", entry.path("category").asText("other"));
    if (entry.hasNonNull("origin_summary")) note.put("origin", entry.path("origin_summary").asText());
    else note.remove("origin");
    ArrayNode examples = note.putArray("examples");
    for (JsonNode example : snapshot.path("examples")) {
      String value = example.path("example_text").asText();
      if (!value.isBlank()) examples.add(value);
    }
    note.set("variants", snapshot.path("variants").deepCopy());
    ObjectNode withdrawal = note.putObject("withdrawal");
    withdrawal.put("entry_id", entryId);
    withdrawal.put("meme_code", entry.path("meme_code").asText());
    withdrawal.put("version", entry.path("current_version").asInt());
    withdrawal.put("withdrawn_at", LocalDateTime.now().toString());
    if (reason != null && !reason.isBlank()) withdrawal.put("reason", reason.trim());
    return mapper.writeValueAsString(note);
  }

  private ObjectNode parseNote(String value) {
    if (value == null || value.isBlank()) return mapper.createObjectNode();
    try {
      JsonNode parsed = mapper.readTree(value);
      return parsed instanceof ObjectNode object ? object : mapper.createObjectNode();
    } catch (Exception ignored) {
      return mapper.createObjectNode();
    }
  }

  private String json(JsonNode value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (Exception exception) {
      throw new IllegalStateException("无法序列化词条快照", exception);
    }
  }
}
