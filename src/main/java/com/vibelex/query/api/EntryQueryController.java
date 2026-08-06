package com.vibelex.query.api;

import com.vibelex.lexicon.application.LexiconSnapshotService;
import com.vibelex.shared.persistence.MyBatisDatabase;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/entries")
public class EntryQueryController {
  private final MyBatisDatabase database;
  private final LexiconSnapshotService snapshots;

  public EntryQueryController(MyBatisDatabase database, LexiconSnapshotService snapshots) {
    this.database = database;
    this.snapshots = snapshots;
  }

  /** 查询允许展示的正式词条；归档词条由调用方显式选择是否包含。 */
  @GetMapping
  public List<Map<String, Object>> list(
      @RequestParam(required = false) String q,
      @RequestParam(defaultValue = "false") boolean includeArchived) {
    String statuses = includeArchived ? "('published','archived')" : "('published')";

    if (q == null || q.isBlank()) {
      return database.list(
          """
                    SELECT e.*, p.risk_level, p.display_enabled
                    FROM meme_entries e
                    JOIN meme_safety_policies p ON p.meme_id = e.id
                    WHERE e.status IN %s
                    ORDER BY e.published_at DESC, e.id DESC
                    LIMIT 200
                    """
              .formatted(statuses));
    }

    String keyword = "%" + q + "%";
    return database.list(
        """
                SELECT DISTINCT e.*, p.risk_level, p.display_enabled
                FROM meme_entries e
                JOIN meme_safety_policies p ON p.meme_id = e.id
                LEFT JOIN meme_variants v ON v.meme_id = e.id
                WHERE e.status IN %s
                  AND (e.canonical_term LIKE ?
                       OR e.normalized_term LIKE ?
                       OR v.variant LIKE ?)
                ORDER BY e.published_at DESC, e.id DESC
                LIMIT 200
                """
            .formatted(statuses),
        keyword,
        keyword,
        keyword);
  }

  @GetMapping("/{id}")
  public Object get(@PathVariable long id) {
    Map<String, Object> policy =
        database.one("SELECT display_enabled FROM meme_safety_policies WHERE meme_id = ?", id);
    if (((Number) policy.get("display_enabled")).intValue() == 0) {
      throw new IllegalStateException("该词条策略禁止公开展示");
    }
    return snapshots.snapshot(id);
  }
}
