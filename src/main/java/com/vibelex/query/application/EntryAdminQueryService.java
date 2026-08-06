package com.vibelex.query.application;

import com.vibelex.lexicon.application.LexiconSnapshotService;
import com.vibelex.shared.persistence.MyBatisDatabase;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class EntryAdminQueryService {

  private static final Set<String> SUPPORTED_STATUSES = Set.of("published", "archived", "all");
  private static final Set<String> SUPPORTED_RISK_LEVELS =
      Set.of("none", "low", "medium", "high", "restricted", "all");

  private final MyBatisDatabase database;
  private final LexiconSnapshotService snapshots;

  public EntryAdminQueryService(MyBatisDatabase database, LexiconSnapshotService snapshots) {
    this.database = database;
    this.snapshots = snapshots;
  }

  public Map<String, Object> list(
      String status, String riskLevel, String query, String source, int page, int size) {
    String selectedStatus = normalize(status, "published");
    String selectedRiskLevel = normalize(riskLevel, "all");
    if (!SUPPORTED_STATUSES.contains(selectedStatus)) {
      throw new IllegalArgumentException("不支持的词条状态: " + selectedStatus);
    }
    if (!SUPPORTED_RISK_LEVELS.contains(selectedRiskLevel)) {
      throw new IllegalArgumentException("不支持的风险等级: " + selectedRiskLevel);
    }

    int safePage = Math.max(page, 1);
    int safeSize = Math.max(10, Math.min(size, 100));
    long offset = (long) (safePage - 1) * safeSize;
    String keyword = query == null ? "" : query.trim();
    String sourceKeyword = source == null ? "" : source.trim();

    StringBuilder where = new StringBuilder(" WHERE 1 = 1");
    List<Object> filterArgs = new ArrayList<>();
    if (!"all".equals(selectedStatus)) {
      where.append(" AND e.status = ?");
      filterArgs.add(selectedStatus);
    }
    if (!"all".equals(selectedRiskLevel)) {
      where.append(" AND p.risk_level = ?");
      filterArgs.add(selectedRiskLevel);
    }
    if (!keyword.isBlank()) {
      where.append(
          """
              AND (e.meme_code LIKE ? OR e.canonical_term LIKE ? OR e.normalized_term LIKE ?
                   OR EXISTS (SELECT 1 FROM meme_variants v
                              WHERE v.meme_id = e.id AND v.variant LIKE ?)
                   OR EXISTS (SELECT 1 FROM meme_senses s
                              WHERE s.meme_id = e.id
                                AND (s.short_definition LIKE ? OR s.definition LIKE ?)))
              """);
      String pattern = "%" + keyword + "%";
      filterArgs.add(pattern);
      filterArgs.add(pattern);
      filterArgs.add(pattern);
      filterArgs.add(pattern);
      filterArgs.add(pattern);
      filterArgs.add(pattern);
    }
    if (!sourceKeyword.isBlank()) {
      where.append(
          """
          AND EXISTS (
              SELECT 1
              FROM meme_evidence evidence
              WHERE evidence.meme_id = e.id
                AND evidence.evidence_role = 'discovery'
                AND evidence.source_name = ?)
          """);
      filterArgs.add(sourceKeyword);
    }

    String from = " FROM meme_entries e JOIN meme_safety_policies p ON p.meme_id = e.id";
    Object totalValue = database.scalar("SELECT COUNT(*)" + from + where, filterArgs.toArray());
    long total = totalValue instanceof Number number ? number.longValue() : 0L;

    List<Object> pageArgs = new ArrayList<>(filterArgs);
    pageArgs.add(safeSize);
    pageArgs.add(offset);
    List<Map<String, Object>> items =
        database.list(
            """
                SELECT e.*, p.risk_level, p.display_enabled,
                       (SELECT s.short_definition
                        FROM meme_senses s
                        WHERE s.meme_id = e.id AND s.status = 'active'
                        ORDER BY s.sense_no
                        LIMIT 1) AS primary_definition
                """
                + from
                + where
                + " ORDER BY e.published_at DESC, e.id DESC LIMIT ? OFFSET ?",
            pageArgs.toArray());

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("items", items);
    result.put("page", safePage);
    result.put("size", safeSize);
    result.put("totalElements", total);
    result.put("totalPages", total == 0 ? 0 : (total + safeSize - 1) / safeSize);
    return result;
  }

  public Map<String, Object> detail(long entryId) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("snapshot", snapshots.snapshot(entryId));
    result.put(
        "revisions",
        database.list(
            """
                SELECT id, version, change_type, change_summary,
                       changed_by, reviewed_by, created_at
                FROM meme_revisions
                WHERE meme_id = ?
                ORDER BY version DESC
                LIMIT 20
                """,
            entryId));
    return result;
  }

  private String normalize(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value.trim();
  }
}
