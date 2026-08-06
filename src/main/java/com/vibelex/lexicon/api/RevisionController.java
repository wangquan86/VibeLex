package com.vibelex.lexicon.api;

import com.vibelex.reviewworkflow.application.ChangeSetService;
import com.vibelex.shared.persistence.MyBatisDatabase;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/admin/entries/{memeId}/revisions")
public class RevisionController {
  private final MyBatisDatabase database;
  private final ObjectMapper mapper;
  private final ChangeSetService changes;

  public RevisionController(
      MyBatisDatabase database, ObjectMapper mapper, ChangeSetService changes) {
    this.database = database;
    this.mapper = mapper;
    this.changes = changes;
  }

  @GetMapping
  public List<Map<String, Object>> list(@PathVariable long memeId) {
    ensurePublished(memeId);
    return database.list(
        """
        SELECT id, meme_id, version, change_type, change_summary,
               changed_by, reviewed_by, created_at
        FROM meme_revisions
        WHERE meme_id = ?
        ORDER BY version DESC
        """,
        memeId);
  }

  @GetMapping("/{version}")
  public Map<String, Object> get(@PathVariable long memeId, @PathVariable int version) {
    ensurePublished(memeId);
    return database.one(
        "SELECT * FROM meme_revisions WHERE meme_id=? AND version=?", memeId, version);
  }

  @GetMapping("/diff")
  public Map<String, Object> diff(
      @PathVariable long memeId, @RequestParam int from, @RequestParam int to) {
    JsonNode a = snapshot(memeId, from), b = snapshot(memeId, to);
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("from", from);
    out.put("to", to);
    out.put("changed", !a.equals(b));
    out.put("meme_entry_changed", !a.path("meme_entry").equals(b.path("meme_entry")));
    out.put("senses_changed", !a.path("senses").equals(b.path("senses")));
    out.put("variants_changed", !a.path("variants").equals(b.path("variants")));
    out.put("match_rules_changed", !a.path("match_rules").equals(b.path("match_rules")));
    out.put("safety_policy_changed", !a.path("safety_policy").equals(b.path("safety_policy")));
    return out;
  }

  @PostMapping("/{version}/rollback")
  public Map<String, Object> rollback(
      @PathVariable long memeId,
      @PathVariable int version,
      @RequestBody(required = false) Map<String, String> body) {
    ensurePublished(memeId);
    return changes.rollback(memeId, version, body == null ? null : body.get("summary"));
  }

  private JsonNode snapshot(long id, int v) {
    ensurePublished(id);
    Object value =
        database.scalar("SELECT snapshot FROM meme_revisions WHERE meme_id=? AND version=?", id, v);
    if (value == null) throw new IllegalArgumentException("版本不存在");
    try {
      return mapper.readTree(String.valueOf(value));
    } catch (Exception e) {
      throw new IllegalStateException("版本快照无效", e);
    }
  }

  private void ensurePublished(long memeId) {
    Object status = database.scalar("SELECT status FROM meme_entries WHERE id=?", memeId);
    if (!"published".equals(status)) throw new IllegalArgumentException("正式词条不存在");
  }
}
