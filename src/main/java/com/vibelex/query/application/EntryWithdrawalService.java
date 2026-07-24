package com.vibelex.query.application;

import com.vibelex.actorcontext.CurrentActorProvider;
import com.vibelex.reviewworkflow.application.ChangeSetService;
import com.vibelex.shared.persistence.MyBatisDatabase;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
    Map<String, Object> detail = entries.detail(entryId);
    ObjectNode snapshot = (ObjectNode) detail.get("snapshot");
    long candidateId = createCandidate(snapshot, entryId, reason);
    int changed =
        database.update(
            "UPDATE meme_entries SET status='archived' WHERE id=? AND status='published'", entryId);
    if (changed == 0) throw new IllegalStateException("词条状态已变化，请刷新后重试");
    database.update(
        "UPDATE meme_safety_policies SET detect_enabled=0, display_enabled=0, generate_enabled=0, recommend_enabled=0 WHERE meme_id=?",
        entryId);
    publishing.removeRecognitionIndex(entryId);
    return Map.of("entryId", entryId, "candidateId", candidateId, "status", "archived");
  }

  @Transactional
  public Map<String, Object> withdrawBatch(List<Long> entryIds, String reason) {
    if (entryIds == null || entryIds.isEmpty()) throw new IllegalArgumentException("请选择要撤回的正式词条");
    List<Long> ids = entryIds.stream().filter(id -> id != null).distinct().toList();
    if (ids.isEmpty() || ids.size() > 100)
      throw new IllegalArgumentException("单次撤回数量必须在 1 到 100 条之间");
    List<Map<String, Object>> results = new ArrayList<>();
    for (Long id : ids) results.add(withdraw(id, reason));
    return Map.of("withdrawnCount", results.size(), "items", results);
  }

  private long createCandidate(ObjectNode snapshot, long entryId, String reason) {
    JsonNode entry = snapshot.path("meme_entry");
    JsonNode sense = snapshot.path("senses").path(0);
    String term = entry.path("canonical_term").asText();
    String definition = sense.path("definition").asText(sense.path("short_definition").asText());
    if (term.isBlank() || definition.isBlank()) throw new IllegalStateException("正式词条快照缺少词形或释义");
    ObjectNode note = mapper.createObjectNode();
    note.put("category", entry.path("category").asText("other"));
    if (entry.hasNonNull("origin_summary"))
      note.put("origin", entry.path("origin_summary").asText());
    ArrayNode examples = note.putArray("examples");
    for (JsonNode example : snapshot.path("examples"))
      examples.add(example.path("example_text").asText());
    note.put("profanity", false);
    note.put("offense", false);
    note.set("variants", snapshot.path("variants"));
    ObjectNode withdrawal = note.putObject("withdrawal");
    withdrawal.put("entry_id", entryId);
    withdrawal.put("meme_code", entry.path("meme_code").asText());
    withdrawal.put("version", entry.path("current_version").asInt());
    withdrawal.put("withdrawn_at", LocalDateTime.now().toString());
    if (reason != null && !reason.isBlank()) withdrawal.put("reason", reason.trim());
    String sourceUrl = firstSourceUrl(snapshot.path("evidence"));
    try {
      return database.insert(
          """
          INSERT INTO candidate_entries(
              import_run_id, import_fingerprint, source_record_key,
              term_raw, normalized_term, definition_raw, source_url,
              parser_version, source_type, created_by, status,
              duplicate_meme_id, processing_note
          ) VALUES (NULL, NULL, ?, ?, ?, ?, ?, 'withdraw-v1', 'manual', ?, 'editing', ?, ?)
          """,
          "withdraw:" + entryId + ":" + UUID.randomUUID(),
          term,
          entry.path("normalized_term").asText(),
          definition,
          sourceUrl,
          actor.currentActor(),
          entryId,
          mapper.writeValueAsString(note));
    } catch (Exception exception) {
      throw new IllegalStateException("无法创建撤回候选词条", exception);
    }
  }

  private String firstSourceUrl(JsonNode evidence) {
    for (JsonNode item : evidence) {
      String url = item.path("source_url").asText();
      if (!url.isBlank()) return url;
    }
    return null;
  }
}
