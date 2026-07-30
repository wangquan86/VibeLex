package com.vibelex.reviewworkflow.application;

import com.vibelex.actorcontext.CurrentActorProvider;
import com.vibelex.candidatediscovery.domain.TermNormalizer;
import com.vibelex.lexicon.application.LexiconSnapshotService;
import com.vibelex.recognition.application.RecognitionIndex;
import com.vibelex.recognitionv2.IndexSyncTaskService;
import com.vibelex.recognitionv2.SemanticIndexService;
import com.vibelex.shared.persistence.MyBatisDatabase;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 单级审核与正式词条发布服务。
 *
 * <p>所有未审核内容仅存在于 entry_change_sets。批准、版本快照与正式子表写入处于同一事务， 任一步失败都会整体回滚，避免查询域看到半成品词条。
 */
@Service
public class ChangeSetService {
  private static final Set<String> SUPPORTED_STATUSES =
      Set.of("draft", "pending_review", "rejected", "approved", "all");

  private final MyBatisDatabase database;
  private final ObjectMapper mapper;
  private final CurrentActorProvider actor;
  private final TermNormalizer normalizer;
  private final LexiconSnapshotService snapshots;
  private final RecognitionIndex recognitionIndex;
  private final SemanticIndexService semanticIndex;
  private final IndexSyncTaskService indexTasks;

  public ChangeSetService(
      MyBatisDatabase database,
      ObjectMapper mapper,
      CurrentActorProvider actor,
      TermNormalizer normalizer,
      LexiconSnapshotService snapshots,
      RecognitionIndex recognitionIndex,
      SemanticIndexService semanticIndex,
      IndexSyncTaskService indexTasks) {
    this.database = database;
    this.mapper = mapper;
    this.actor = actor;
    this.normalizer = normalizer;
    this.snapshots = snapshots;
    this.recognitionIndex = recognitionIndex;
    this.semanticIndex = semanticIndex;
    this.indexTasks = indexTasks;
  }

  public Map<String, Object> list(String status, String query, int page, int size) {
    String selectedStatus = status == null || status.isBlank() ? "pending_review" : status.trim();
    if (!SUPPORTED_STATUSES.contains(selectedStatus)) {
      throw new IllegalArgumentException("不支持的审核状态: " + selectedStatus);
    }

    int safePage = Math.max(page, 1);
    int safeSize = Math.max(10, Math.min(size, 100));
    long offset = (long) (safePage - 1) * safeSize;
    String keyword = query == null ? "" : query.trim();
    StringBuilder where = new StringBuilder(" WHERE 1 = 1");
    List<Object> filterArgs = new ArrayList<>();
    if (!"all".equals(selectedStatus)) {
      where.append(" AND status = ?");
      filterArgs.add(selectedStatus);
    }
    if (!keyword.isBlank()) {
      where.append(
          " AND (change_summary LIKE ? OR created_by LIKE ? OR submitted_by LIKE ? OR reviewed_by LIKE ?)");
      String pattern = "%" + keyword + "%";
      filterArgs.add(pattern);
      filterArgs.add(pattern);
      filterArgs.add(pattern);
      filterArgs.add(pattern);
    }

    Object totalValue =
        database.scalar("SELECT COUNT(*) FROM entry_change_sets" + where, filterArgs.toArray());
    long total = totalValue instanceof Number number ? number.longValue() : 0L;
    List<Object> pageArgs = new ArrayList<>(filterArgs);
    pageArgs.add(safeSize);
    pageArgs.add(offset);
    List<Map<String, Object>> items =
        database.list(
            """
                SELECT id, meme_id, change_type, base_version, status,
                       change_summary, created_by, submitted_by, submitted_at,
                       reviewed_by, reviewed_at, review_comment, created_at, updated_at
                FROM entry_change_sets
                """
                + where
                + " ORDER BY id DESC LIMIT ? OFFSET ?",
            pageArgs.toArray());

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("items", items);
    result.put("page", safePage);
    result.put("size", safeSize);
    result.put("totalElements", total);
    result.put("totalPages", total == 0 ? 0 : (total + safeSize - 1) / safeSize);
    return result;
  }

  public Map<String, Object> get(long id) {
    return database.one("SELECT * FROM entry_change_sets WHERE id=?", id);
  }

  public Map<String, Object> detail(long id) {
    Map<String, Object> changeSet = new LinkedHashMap<>(get(id));
    changeSet.put("proposed_snapshot", parse(String.valueOf(changeSet.get("proposed_snapshot"))));
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("changeSet", changeSet);
    Long memeId = numberAsLong(changeSet.get("meme_id"));
    result.put("currentSnapshot", memeId == null ? null : snapshots.snapshot(memeId));
    return result;
  }

  @Transactional
  public Map<String, Object> create(
      Long memeId, String type, Integer baseVersion, JsonNode snapshot, String summary) {
    String changeType = type == null ? (memeId == null ? "create" : "update") : type;
    if (!Set.of("create", "update").contains(changeType)) {
      throw new IllegalArgumentException("change_type 只能为 create 或 update");
    }
    if ("create".equals(changeType) && memeId != null) {
      throw new IllegalArgumentException("create 不应指定 meme_id");
    }
    if ("update".equals(changeType)) {
      if (memeId == null) {
        throw new IllegalArgumentException("update 必须指定 meme_id");
      }
      if (baseVersion == null) {
        baseVersion =
            numberAsInt(
                database.scalar("SELECT current_version FROM meme_entries WHERE id=?", memeId));
      }
    }
    validateSnapshot(snapshot);
    long id =
        database.insert(
            """
            INSERT INTO entry_change_sets(
                meme_id, change_type, base_version, proposed_snapshot,
                change_summary, created_by
            ) VALUES (?, ?, ?, ?, ?, ?)
            """,
            memeId,
            changeType,
            baseVersion,
            json(snapshot),
            summary,
            actor.currentActor());
    return get(id);
  }

  @Transactional
  public void update(long id, JsonNode snapshot, String summary) {
    validateSnapshot(snapshot);
    int n =
        database.update(
            """
            UPDATE entry_change_sets
            SET proposed_snapshot = ?, change_summary = ?
            WHERE id = ? AND status = 'draft'
            """,
            json(snapshot),
            summary,
            id);
    if (n == 0) {
      throw new IllegalStateException("只有 draft 可编辑");
    }
  }

  @Transactional
  public void transition(long id, String action, String comment) {
    if ("reject".equals(action) && (comment == null || comment.isBlank())) {
      throw new IllegalArgumentException("驳回时必须填写审核意见");
    }
    String who = actor.currentActor();
    int n =
        switch (action) {
          case "submit" ->
              database.update(
                  """
                  UPDATE entry_change_sets
                  SET status = 'pending_review', submitted_by = ?, submitted_at = ?
                  WHERE id = ? AND status = 'draft'
                  """,
                  who,
                  LocalDateTime.now(),
                  id);
          case "withdraw" ->
              database.update(
                  """
                  UPDATE entry_change_sets
                  SET status = 'draft', submitted_by = NULL, submitted_at = NULL
                  WHERE id = ? AND status = 'pending_review'
                  """,
                  id);
          case "reject" ->
              database.update(
                  """
                  UPDATE entry_change_sets
                  SET status = 'rejected', reviewed_by = ?, reviewed_at = ?,
                      review_comment = ?
                  WHERE id = ? AND status = 'pending_review'
                  """,
                  who,
                  LocalDateTime.now(),
                  comment,
                  id);
          case "reopen" ->
              database.update(
                  """
                  UPDATE entry_change_sets
                  SET status = 'draft', reviewed_by = NULL, reviewed_at = NULL,
                      review_comment = NULL
                  WHERE id = ? AND status = 'rejected'
                  """,
                  id);
          default -> throw new IllegalArgumentException("未知状态操作");
        };
    if (n == 0) {
      throw new IllegalStateException("当前状态不允许该操作");
    }
  }

  @Transactional
  public Map<String, Object> approve(long id, String comment) {
    Map<String, Object> cs = lockChangeSet(id);
    if (!"pending_review".equals(cs.get("status"))) {
      throw new IllegalStateException("只有 pending_review 可批准");
    }
    JsonNode proposed = parse(String.valueOf(cs.get("proposed_snapshot")));
    validateSnapshot(proposed);
    String reviewer = actor.currentActor();
    Long memeId = numberAsLong(cs.get("meme_id"));
    int nextVersion;
    // 更新已有词条时锁定主记录并校验编辑基线，防止覆盖并发修改。
    if (memeId == null) {
      memeId = createEntry(proposed.path("meme_entry"), reviewer);
      nextVersion = 1;
    } else {
      Map<String, Object> entry = lockEntry(memeId);
      int current = ((Number) entry.get("current_version")).intValue();
      int base = ((Number) cs.get("base_version")).intValue();
      if (current != base) {
        throw new IllegalStateException("词条已被他人修改，请刷新后重试");
      }
      nextVersion = current + 1;
      clearChildren(memeId);
      updateEntry(memeId, proposed.path("meme_entry"), reviewer, nextVersion);
    }
    writeChildren(memeId, proposed, reviewer);
    database.update(
        """
        UPDATE meme_entries
        SET current_version = ?, reviewed_by = ?,
            published_at = COALESCE(published_at, ?)
        WHERE id = ?
        """,
        nextVersion,
        reviewer,
        LocalDateTime.now(),
        memeId);
    JsonNode actual = snapshots.snapshot(memeId);
    database.insert(
        """
        INSERT INTO meme_revisions(
            meme_id, version, change_type, change_summary,
            snapshot, changed_by, reviewed_by
        ) VALUES (?, ?, ?, ?, ?, ?, ?)
        """,
        memeId,
        nextVersion,
        cs.get("change_type"),
        cs.get("change_summary"),
        json(actual),
        cs.get("created_by"),
        reviewer);
    database.update(
        """
        UPDATE entry_change_sets
        SET meme_id = ?, status = 'approved', reviewed_by = ?,
            reviewed_at = ?, review_comment = ?
        WHERE id = ?
        """,
        memeId,
        reviewer,
        LocalDateTime.now(),
        comment,
        id);
    refreshIndexes(memeId);
    return database.one("SELECT * FROM meme_entries WHERE id=?", memeId);
  }

  /** Publishes an approved candidate directly without creating a change-set draft. */
  @Transactional
  public Map<String, Object> publishCandidate(
      Long memeId, Integer baseVersion, JsonNode proposed, String summary, String submittedBy) {
    return publishCandidate(memeId, baseVersion, proposed, summary, submittedBy, true);
  }

  @Transactional
  public Map<String, Object> publishCandidate(
      Long memeId,
      Integer baseVersion,
      JsonNode proposed,
      String summary,
      String submittedBy,
      boolean refreshIndex) {
    validateSnapshot(proposed);
    String reviewer = actor.currentActor();
    int nextVersion;
    String changeType;
    if (memeId == null) {
      memeId = createEntry(proposed.path("meme_entry"), reviewer);
      nextVersion = 1;
      changeType = "create";
    } else {
      Map<String, Object> entry = lockEntry(memeId);
      int current = ((Number) entry.get("current_version")).intValue();
      if (baseVersion != null && current != baseVersion) {
        throw new IllegalStateException("正式词条已发生变化，请退回候选后重新提交审核");
      }
      nextVersion = current + 1;
      changeType = "update";
      clearChildren(memeId);
      updateEntry(memeId, proposed.path("meme_entry"), reviewer, nextVersion);
    }
    writeChildren(memeId, proposed, reviewer);
    database.update(
        """
        UPDATE meme_entries
        SET current_version = ?, reviewed_by = ?,
            published_at = COALESCE(published_at, ?)
        WHERE id = ?
        """,
        nextVersion,
        reviewer,
        LocalDateTime.now(),
        memeId);
    JsonNode actual = snapshots.snapshot(memeId);
    database.insert(
        """
        INSERT INTO meme_revisions(
            meme_id, version, change_type, change_summary,
            snapshot, changed_by, reviewed_by
        ) VALUES (?, ?, ?, ?, ?, ?, ?)
        """,
        memeId,
        nextVersion,
        changeType,
        summary,
        json(actual),
        submittedBy,
        reviewer);
    if (refreshIndex) {
      refreshIndexes(memeId);
    }
    return database.one("SELECT * FROM meme_entries WHERE id=?", memeId);
  }

  public void refreshRecognitionIndex() {
    recognitionIndex.refresh();
    semanticIndex.rebuildAllAfterCommit();
  }

  /** 刷新单条正式词条的 V1 内存索引与 V2 ES 投影。 */
  public void refreshRecognitionIndex(long memeId) {
    refreshIndexes(memeId);
  }

  public void removeRecognitionIndex(long memeId) {
    recognitionIndex.refresh();
    indexTasks.enqueueAfterCommit(memeId, "DELETE");
  }

  @Transactional
  public Map<String, Object> rollback(long memeId, int targetVersion, String summary) {
    // 回滚始终追加新版本，绝不复用历史版本号或删除审计记录。
    Map<String, Object> entry = lockEntry(memeId);
    Object oldValue =
        database.scalar(
            "SELECT snapshot FROM meme_revisions WHERE meme_id=? AND version=?",
            memeId,
            targetVersion);
    String old = oldValue == null ? null : String.valueOf(oldValue);
    if (old == null) {
      throw new IllegalArgumentException("目标版本不存在");
    }
    JsonNode target = parse(old);
    int current = ((Number) entry.get("current_version")).intValue();
    Integer max =
        numberAsInt(
            database.scalar("SELECT MAX(version) FROM meme_revisions WHERE meme_id=?", memeId));
    int next = Math.max(current, max == null ? 0 : max) + 1;
    clearChildren(memeId);
    updateEntry(memeId, target.path("meme_entry"), actor.currentActor(), next);
    writeChildren(memeId, target, actor.currentActor());
    JsonNode actual = snapshots.snapshot(memeId);
    database.insert(
        """
        INSERT INTO meme_revisions(
            meme_id, version, change_type, change_summary,
            snapshot, changed_by, reviewed_by
        ) VALUES (?, ?, 'rollback', ?, ?, ?, ?)
        """,
        memeId,
        next,
        summary == null ? "回滚自版本 " + targetVersion : summary,
        json(actual),
        actor.currentActor(),
        actor.currentActor());
    refreshIndexes(memeId);
    return database.one("SELECT * FROM meme_entries WHERE id=?", memeId);
  }

  private void refreshIndexes(long memeId) {
    recognitionIndex.refresh();
    indexTasks.enqueueAfterCommit(memeId, "UPSERT");
  }

  private long createEntry(JsonNode e, String reviewer) {
    String language = text(e, "language_code", "zh-CN");
    String term = required(e, "canonical_term");
    String normalized = normalizer.normalize(term, language);
    String temp = "PENDING_" + UUID.randomUUID();
    long id =
        database.insert(
            """
            INSERT INTO meme_entries(
                meme_code, canonical_term, normalized_term, language_code,
                category, domain_tags, origin_summary, trend_status,
                heat_score, status, current_version, created_by,
                reviewed_by, published_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'published', 1, ?, ?, ?)
            """,
            temp,
            term,
            normalized,
            language,
            text(e, "category", "other"),
            jsonOrNull(e.get("domain_tags")),
            nullableText(e, "origin_summary"),
            text(e, "trend_status", "untracked"),
            decimal(e, "heat_score"),
            actor.currentActor(),
            reviewer,
            LocalDateTime.now());
    database.update(
        "UPDATE meme_entries SET meme_code = ? WHERE id = ?", String.format("MEME_%06d", id), id);
    return id;
  }

  private void updateEntry(long id, JsonNode e, String reviewer, int version) {
    String language = text(e, "language_code", "zh-CN");
    String term = required(e, "canonical_term");
    database.update(
        """
        UPDATE meme_entries
        SET canonical_term = ?, normalized_term = ?, language_code = ?,
            category = ?, domain_tags = ?, origin_summary = ?,
            trend_status = ?, heat_score = ?, status = ?,
            current_version = ?, reviewed_by = ?
        WHERE id = ?
        """,
        term,
        normalizer.normalize(term, language),
        language,
        text(e, "category", "other"),
        jsonOrNull(e.get("domain_tags")),
        nullableText(e, "origin_summary"),
        text(e, "trend_status", "untracked"),
        decimal(e, "heat_score"),
        text(e, "status", "published"),
        version,
        reviewer,
        id);
  }

  private void clearChildren(long id) {
    database.update("DELETE FROM meme_variants WHERE meme_id=?", id);
    database.update("DELETE FROM meme_examples WHERE meme_id=?", id);
    database.update("DELETE FROM meme_match_rules WHERE meme_id=?", id);
    database.update("DELETE FROM meme_evidence WHERE meme_id=?", id);
    database.update("DELETE FROM meme_safety_policies WHERE meme_id=?", id);
    database.update("DELETE FROM meme_senses WHERE meme_id=?", id);
  }

  private void writeChildren(long memeId, JsonNode root, String reviewer) {
    Map<Integer, Long> senseIds = new HashMap<>();
    for (JsonNode s : root.path("senses")) {
      int no = s.path("sense_no").asInt(senseIds.size() + 1);
      long sid =
          insert(
              """
              INSERT INTO meme_senses(
                  meme_id, sense_no, short_definition, definition,
                  usage_context, non_usage_context, semantic_tags,
                  emotion_tags, safety_policy_override, polarity,
                  formality, status
              ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
              """,
              memeId,
              no,
              required(s, "short_definition"),
              required(s, "definition"),
              jsonOrNull(s.get("usage_context")),
              jsonOrNull(s.get("non_usage_context")),
              jsonOrNull(s.get("semantic_tags")),
              jsonOrNull(s.get("emotion_tags")),
              jsonOrNull(s.get("safety_policy_override")),
              text(s, "polarity", "neutral"),
              text(s, "formality", "informal"),
              text(s, "status", "active"));
      senseIds.put(no, sid);
    }
    for (JsonNode v : root.path("variants")) {
      String type = required(v, "variant_type");
      String value = required(v, "variant");
      insert(
          """
          INSERT INTO meme_variants(
              meme_id, sense_id, variant, normalized_variant,
              variant_type, confidence, source_method, status
          ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
          """,
          memeId,
          senseId(v, senseIds),
          value,
          normalizer.normalize(
              value,
              text(root.path("meme_entry"), "language_code", "zh-CN"),
              normalizer.profileForVariant(type)),
          type,
          decimalOr(v, "confidence", 1),
          text(v, "source_method", "editorial"),
          text(v, "status", "active"));
    }
    for (JsonNode x : root.path("examples")) {
      insert(
          """
          INSERT INTO meme_examples(
              meme_id, sense_id, example_text, example_role,
              explanation, status, created_by, reviewed_by
          ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
          """,
          memeId,
          senseId(x, senseIds),
          required(x, "example_text"),
          text(x, "example_role", "positive"),
          nullableText(x, "explanation"),
          text(x, "status", "approved"),
          actor.currentActor(),
          reviewer);
    }
    for (JsonNode r : root.path("match_rules")) {
      insert(
          """
          INSERT INTO meme_match_rules(
              meme_id, sense_id, rule_type, rule_value, rule_config,
              weight, threshold, priority, enabled, created_by
          ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
          """,
          memeId,
          senseId(r, senseIds),
          required(r, "rule_type"),
          required(r, "rule_value"),
          jsonOrNull(r.get("rule_config")),
          decimalOr(r, "weight", 1),
          decimal(r, "threshold"),
          r.path("priority").asInt(100),
          r.path("enabled").asBoolean(true) ? 1 : 0,
          actor.currentActor());
    }
    JsonNode p = root.path("safety_policy");
    insert(
        """
        INSERT INTO meme_safety_policies(
            meme_id, profanity, offense, risk_tags, risk_level,
            detect_enabled, display_enabled, generate_enabled,
            recommend_enabled, moderation_policy, notes
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        memeId,
        bool(p, "profanity"),
        bool(p, "offense"),
        jsonOrNull(p.get("risk_tags")),
        text(p, "risk_level", "low"),
        boolDefault(p, "detect_enabled", 1),
        boolDefault(p, "display_enabled", 1),
        boolDefault(p, "generate_enabled", 1),
        boolDefault(p, "recommend_enabled", 1),
        text(p, "moderation_policy", "normal"),
        nullableText(p, "notes"));
    for (JsonNode e : root.path("evidence")) {
      insert(
          """
          INSERT INTO meme_evidence(
              meme_id, sense_id, source_layer, source_name, source_url,
              evidence_role, evidence_note, observed_at, confidence, status
          ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
          """,
          memeId,
          senseId(e, senseIds),
          required(e, "source_layer"),
          required(e, "source_name"),
          nullableText(e, "source_url"),
          required(e, "evidence_role"),
          nullableText(e, "evidence_note"),
          null,
          decimal(e, "confidence"),
          text(e, "status", "active"));
    }
  }

  private void validateSnapshot(JsonNode root) {
    if (root == null || !root.isObject()) {
      throw new IllegalArgumentException("snapshot 必须为对象");
    }
    JsonNode entry = root.path("meme_entry");
    required(entry, "canonical_term");
    if (!root.path("senses").isArray() || root.path("senses").isEmpty()) {
      throw new IllegalArgumentException("发布至少需要一个义项");
    }
    if (!root.path("safety_policy").isObject()) {
      throw new IllegalArgumentException("发布必须配置风险策略");
    }
    boolean lexical = !root.path("variants").isEmpty();
    for (JsonNode r : root.path("match_rules")) {
      if (Set.of("exact_match", "normalized_match", "pinyin_match", "regex_match")
          .contains(r.path("rule_type").asText())) {
        lexical = true;
      }
    }
    if (!lexical) {
      throw new IllegalArgumentException("发布至少需要一条词面规则或变体");
    }
    if (root.path("evidence").isEmpty()) {
      throw new IllegalArgumentException("发布至少需要一条证据");
    }
  }

  private Map<String, Object> lockChangeSet(long id) {
    return database.one("SELECT * FROM entry_change_sets WHERE id=? FOR UPDATE", id);
  }

  private Map<String, Object> lockEntry(long id) {
    return database.one("SELECT * FROM meme_entries WHERE id=? FOR UPDATE", id);
  }

  private long insert(String sql, Object... args) {
    return database.insert(sql, args);
  }

  private JsonNode parse(String s) {
    try {
      return mapper.readTree(s);
    } catch (Exception e) {
      throw new IllegalStateException("snapshot JSON 无效", e);
    }
  }

  private String json(JsonNode n) {
    try {
      return mapper.writeValueAsString(n);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private String jsonOrNull(JsonNode n) {
    return n == null || n.isNull() || n.isMissingNode() ? null : json(n);
  }

  private String required(JsonNode n, String f) {
    String v = nullableText(n, f);
    if (v == null || v.isBlank()) throw new IllegalArgumentException(f + " 必填");
    return v;
  }

  private String text(JsonNode n, String f, String d) {
    String v = nullableText(n, f);
    return v == null || v.isBlank() ? d : v;
  }

  private String nullableText(JsonNode n, String f) {
    JsonNode v = n.get(f);
    return v == null || v.isNull() ? null : v.asText();
  }

  private Object decimal(JsonNode n, String f) {
    return n.hasNonNull(f) ? n.get(f).decimalValue() : null;
  }

  private Object decimalOr(JsonNode n, String f, double d) {
    return n.hasNonNull(f) ? n.get(f).decimalValue() : d;
  }

  private int bool(JsonNode n, String f) {
    return n.path(f).asBoolean(false) ? 1 : 0;
  }

  private int boolDefault(JsonNode n, String f, int d) {
    return n.has(f) ? bool(n, f) : d;
  }

  private Long senseId(JsonNode n, Map<Integer, Long> ids) {
    if (!n.hasNonNull("sense_no")) return null;
    Long id = ids.get(n.get("sense_no").asInt());
    if (id == null) throw new IllegalArgumentException("引用了不存在的 sense_no");
    return id;
  }

  private Integer numberAsInt(Object value) {
    return value instanceof Number number ? number.intValue() : null;
  }

  private Long numberAsLong(Object value) {
    return value instanceof Number number ? number.longValue() : null;
  }
}
