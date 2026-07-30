package com.vibelex.candidatediscovery.application;

import com.vibelex.actorcontext.CurrentActorProvider;
import com.vibelex.candidatediscovery.domain.TermNormalizer;
import com.vibelex.crawling.CrawlConnector.CrawledEntry;
import com.vibelex.llm.AiVariantGenerator;
import com.vibelex.reviewworkflow.application.ChangeSetService;
import com.vibelex.shared.persistence.MyBatisDatabase;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 候选发现域应用服务。
 *
 * <p>候选只允许转换成 change set，绝不会直接写入正式词条表。
 */
@Service
public class CandidateService {

  private static final Logger log = LoggerFactory.getLogger(CandidateService.class);

  private static final Set<String> SUPPORTED_STATUSES =
      Set.of("editing", "pending_review", "returned", "published", "all");
  private static final Set<String> FIXED_CATEGORIES =
      Set.of("other", "slang", "homophone", "abbreviation", "template_phrase");

  private final MyBatisDatabase database;
  private final ObjectMapper mapper;
  private final CurrentActorProvider actorProvider;
  private final TermNormalizer normalizer;
  private final ChangeSetService publishingService;
  private final AiVariantGenerator variantGenerator;

  public CandidateService(
      MyBatisDatabase database,
      ObjectMapper mapper,
      CurrentActorProvider actorProvider,
      TermNormalizer normalizer,
      ChangeSetService publishingService,
      AiVariantGenerator variantGenerator) {
    this.database = database;
    this.mapper = mapper;
    this.actorProvider = actorProvider;
    this.normalizer = normalizer;
    this.publishingService = publishingService;
    this.variantGenerator = variantGenerator;
  }

  public Map<String, Object> list(String status, int page, int size, String query, String source) {
    String selectedStatus = status == null || status.isBlank() ? "editing" : status.trim();
    if (!SUPPORTED_STATUSES.contains(selectedStatus)) {
      throw new IllegalArgumentException("不支持的候选状态: " + selectedStatus);
    }

    int safePage = Math.max(page, 1);
    int safeSize = Math.max(10, Math.min(size, 100));
    long offset = (long) (safePage - 1) * safeSize;
    String keyword = query == null ? "" : query.trim();
    String sourceKeyword = source == null ? "" : source.trim();

    StringBuilder where = new StringBuilder(" WHERE 1 = 1");
    List<Object> filterArgs = new ArrayList<>();
    if (!"all".equals(selectedStatus)) {
      where.append(" AND c.status = ?");
      filterArgs.add(selectedStatus);
    } else {
      where.append(" AND c.status IN ('editing', 'pending_review', 'returned', 'published')");
    }
    if (!keyword.isBlank()) {
      where.append(
          " AND (c.term_raw LIKE ? OR c.normalized_term LIKE ? OR c.definition_raw LIKE ?)");
      String pattern = "%" + keyword + "%";
      filterArgs.add(pattern);
      filterArgs.add(pattern);
      filterArgs.add(pattern);
    }
    if (!sourceKeyword.isBlank()) {
      where.append(
          " AND CASE WHEN c.source_type = 'crawler' THEN COALESCE(JSON_UNQUOTE(JSON_EXTRACT(c.processing_note, '$.source_name')), JSON_UNQUOTE(JSON_EXTRACT(c.processing_note, '$.source_code')), '未知来源') ELSE COALESCE(r.source_name, '人工录入') END = ?");
      filterArgs.add(sourceKeyword);
    }

    Object totalValue =
        database.scalar(
            "SELECT COUNT(*) FROM candidate_entries c LEFT JOIN source_import_runs r ON r.id = c.import_run_id"
                + where,
            filterArgs.toArray());
    long total = totalValue instanceof Number number ? number.longValue() : 0L;

    List<Object> pageArgs = new ArrayList<>(filterArgs);
    pageArgs.add(safeSize);
    pageArgs.add(offset);
    List<Map<String, Object>> items =
        database.list(
            """
                SELECT c.*, CASE WHEN c.source_type = 'crawler' THEN COALESCE(JSON_UNQUOTE(JSON_EXTRACT(c.processing_note, '$.source_name')), JSON_UNQUOTE(JSON_EXTRACT(c.processing_note, '$.source_code')), '未知来源')
                                  ELSE COALESCE(r.source_name, '人工录入') END AS source_name,
                       CASE WHEN c.source_type = 'crawler' THEN c.parser_version
                            ELSE COALESCE(r.source_version, 'manual') END AS source_version,
                       r.file_name, r.source_url AS import_source_url
                FROM candidate_entries c
                LEFT JOIN source_import_runs r ON r.id = c.import_run_id
                """
                + where
                + " ORDER BY c.id DESC LIMIT ? OFFSET ?",
            pageArgs.toArray());

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("items", items);
    result.put("page", safePage);
    result.put("size", safeSize);
    result.put("totalElements", total);
    result.put("totalPages", total == 0 ? 0 : (total + safeSize - 1) / safeSize);
    return result;
  }

  public Map<String, Object> detail(long candidateId) {
    return database.one(
        """
            SELECT c.*, CASE WHEN c.source_type = 'crawler' THEN COALESCE(JSON_UNQUOTE(JSON_EXTRACT(c.processing_note, '$.source_name')), JSON_UNQUOTE(JSON_EXTRACT(c.processing_note, '$.source_code')), '未知来源')
                              ELSE COALESCE(r.source_name, '人工录入') END AS source_name,
                   CASE WHEN c.source_type = 'crawler' THEN c.parser_version
                        ELSE COALESCE(r.source_version, 'manual') END AS source_version,
                   r.file_name, r.source_url AS import_source_url
            FROM candidate_entries c
            LEFT JOIN source_import_runs r ON r.id = c.import_run_id
            WHERE c.id = ?
            """,
        candidateId);
  }

  @Transactional
  public CrawlerImportResult createFromCrawler(
      String sourceCode, String sourceName, CrawledEntry entry, String createdBy) {
    if (sourceName == null || sourceName.isBlank()) {
      throw new IllegalArgumentException("爬虫来源名称不能为空");
    }
    if (createdBy == null || createdBy.isBlank()) {
      throw new IllegalArgumentException("爬虫操作者不能为空");
    }
    validateCandidate(entry.term(), entry.definition());
    String normalized = normalizer.normalize(entry.term(), "zh-CN");

    Long memeId =
        numberAsLong(
            database.scalar(
                "SELECT id FROM meme_entries WHERE normalized_term = ? LIMIT 1", normalized));
    if (memeId != null) {
      return new CrawlerImportResult("duplicate", null, "meme", memeId, normalized);
    }

    Long variantMemeId =
        numberAsLong(
            database.scalar(
                "SELECT meme_id FROM meme_variants WHERE normalized_variant = ? AND status = 'active' LIMIT 1",
                normalized));
    if (variantMemeId != null) {
      return new CrawlerImportResult("duplicate", null, "variant", variantMemeId, normalized);
    }

    Long candidateId =
        numberAsLong(
            database.scalar(
                "SELECT id FROM candidate_entries WHERE normalized_term = ? LIMIT 1", normalized));
    if (candidateId != null) {
      return new CrawlerImportResult("duplicate", null, "candidate", candidateId, normalized);
    }

    ObjectNode note = mapper.createObjectNode();
    note.put("source_code", sourceCode);
    note.put("source_name", sourceName.trim());
    note.put("source_record_key", entry.sourceRecordKey());
    note.put("category", FIXED_CATEGORIES.contains(entry.category()) ? entry.category() : "other");
    if (entry.sourceCategory() != null && !entry.sourceCategory().isBlank()) {
      note.put("source_category", entry.sourceCategory().trim());
    }
    appendCrawlerStrings(note.putArray("source_tags"), entry.sourceTags(), 20, 64);
    appendCrawlerStrings(note.putArray("examples"), entry.examples(), 20, 2000);
    note.put("profanity", false);
    note.put("offense", false);
    note.putArray("variants");
    long createdId =
        database.insert(
            """
            INSERT INTO candidate_entries(
                import_run_id, import_fingerprint, source_record_key,
                term_raw, normalized_term, definition_raw, source_url,
                parser_version, source_type, created_by, status,
                duplicate_meme_id, processing_note
            ) VALUES (NULL, NULL, ?, ?, ?, ?, ?, ?, 'crawler', ?, 'editing', NULL, ?)
            """,
            crawlerRecordKey(sourceCode, entry.sourceRecordKey()),
            entry.term().trim(),
            normalized,
            entry.definition().trim(),
            blankToNull(entry.sourceUrl()),
            entry.parserVersion(),
            createdBy.trim(),
            toJson(note));
    return new CrawlerImportResult("imported", createdId, null, null, normalized);
  }

  public record CrawlerImportResult(
      String status,
      Long candidateId,
      String duplicateTargetType,
      Long duplicateTargetId,
      String normalizedTerm) {}

  @Transactional
  @SuppressWarnings("DuplicatedCode") // Public command signature intentionally mirrors update.
  public Map<String, Object> create(
      String term,
      String definition,
      String category,
      String origin,
      List<String> examples,
      boolean profanity,
      boolean offense,
      String sourceUrl,
      List<com.vibelex.candidatediscovery.api.CandidateController.VariantRequest> variants) {
    validateCandidate(term, definition);
    String normalized = normalizer.normalize(term, "zh-CN");
    Long duplicateMemeId = findDuplicate(normalized);
    long id =
        database.insert(
            """
                INSERT INTO candidate_entries(
                    import_run_id, import_fingerprint, source_record_key,
                    term_raw, normalized_term, definition_raw, source_url,
                    parser_version, source_type, created_by, status,
                    duplicate_meme_id, processing_note
                ) VALUES (NULL, NULL, ?, ?, ?, ?, ?, 'manual-v1', 'manual', ?, 'editing', ?, ?)
                """,
            "manual:" + UUID.randomUUID(),
            term.trim(),
            normalized,
            definition.trim(),
            blankToNull(sourceUrl),
            actorProvider.currentActor(),
            duplicateMemeId,
            editorNote(null, term, category, origin, examples, profanity, offense, variants));
    return detail(id);
  }

  @Transactional
  @SuppressWarnings("DuplicatedCode") // Compatibility overload delegates to the variant-aware API.
  public Map<String, Object> update(
      long candidateId,
      String term,
      String definition,
      String category,
      String origin,
      List<String> examples,
      boolean profanity,
      boolean offense,
      String sourceUrl) {
    return update(
        candidateId,
        term,
        definition,
        category,
        origin,
        examples,
        profanity,
        offense,
        sourceUrl,
        null);
  }

  @Transactional
  @SuppressWarnings("DuplicatedCode") // Public command signature intentionally mirrors create.
  public Map<String, Object> update(
      long candidateId,
      String term,
      String definition,
      String category,
      String origin,
      List<String> examples,
      boolean profanity,
      boolean offense,
      String sourceUrl,
      List<com.vibelex.candidatediscovery.api.CandidateController.VariantRequest> variants) {
    validateCandidate(term, definition);
    Map<String, Object> candidate = findCandidateForUpdate(candidateId);
    ensureEditable(candidate);
    String normalized = normalizer.normalize(term, "zh-CN");
    Long duplicateMemeId = findDuplicate(normalized);
    int changed =
        database.update(
            """
                UPDATE candidate_entries
                SET term_raw = ?, normalized_term = ?, definition_raw = ?,
                    source_url = ?, duplicate_meme_id = ?, processing_note = ?
                WHERE id = ? AND status IN ('editing', 'returned')
                """,
            term.trim(),
            normalized,
            definition.trim(),
            blankToNull(sourceUrl),
            duplicateMemeId,
            editorNote(
                stringValue(candidate.get("processing_note")),
                term,
                category,
                origin,
                examples,
                profanity,
                offense,
                variants),
            candidateId);
    if (changed == 0) {
      throw new IllegalStateException("审核中的候选词条不允许编辑");
    }
    return detail(candidateId);
  }

  @Transactional
  public Map<String, Object> submit(long candidateId) {
    Map<String, Object> candidate = findCandidateForUpdate(candidateId);
    ensureEditable(candidate);
    validateCandidate(
        stringValue(candidate.get("term_raw")), stringValue(candidate.get("definition_raw")));
    Long duplicateMemeId = numberAsLong(candidate.get("duplicate_meme_id"));
    Integer baseVersion =
        duplicateMemeId == null
            ? null
            : numberAsInt(
                database.scalar(
                    "SELECT current_version FROM meme_entries WHERE id = ?", duplicateMemeId));
    String actor = actorProvider.currentActor();
    database.update(
        """
            UPDATE candidate_entries
            SET status = 'pending_review', submitted_by = ?, submitted_at = ?,
                review_base_version = ?, reviewed_by = NULL, reviewed_at = NULL,
                review_comment = NULL
            WHERE id = ? AND status IN ('editing', 'returned')
            """,
        actor,
        java.time.LocalDateTime.now(),
        baseVersion,
        candidateId);
    return detail(candidateId);
  }

  @Transactional
  public Map<String, Object> batchSubmit(List<Long> candidateIds) {
    List<Long> ids = validateBatchIds(candidateIds, "提交审核");
    for (Long id : ids) {
      submit(id);
    }
    return Map.of("submittedCount", ids.size(), "ids", ids);
  }

  @Transactional
  public Map<String, Object> batchApprove(List<Long> candidateIds, String comment) {
    List<Long> ids = validateBatchIds(candidateIds, "批准");
    for (Long id : ids) {
      Map<String, Object> published = approveInternal(id, comment, false);
      publishingService.refreshRecognitionIndex(((Number) published.get("id")).longValue());
    }
    return Map.of("approvedCount", ids.size(), "ids", ids);
  }

  @Transactional
  public Map<String, Object> batchReturn(List<Long> candidateIds, String comment) {
    if (comment == null || comment.isBlank()) {
      throw new IllegalArgumentException("批量退回时必须填写审核意见");
    }
    List<Long> ids = validateBatchIds(candidateIds, "退回");
    for (Long id : ids) {
      returnForEditing(id, comment);
    }
    return Map.of("returnedCount", ids.size(), "ids", ids);
  }

  @Transactional
  public Map<String, Object> returnForEditing(long candidateId, String comment) {
    if (comment == null || comment.isBlank()) {
      throw new IllegalArgumentException("退回时必须填写审核意见");
    }
    int changed =
        database.update(
            """
                UPDATE candidate_entries
                SET status = 'returned', reviewed_by = ?, reviewed_at = ?, review_comment = ?
                WHERE id = ? AND status = 'pending_review'
                """,
            actorProvider.currentActor(),
            java.time.LocalDateTime.now(),
            comment.trim(),
            candidateId);
    if (changed == 0) {
      throw new IllegalStateException("只有审核中的候选词条可以退回");
    }
    return detail(candidateId);
  }

  @Transactional
  public Map<String, Object> approve(long candidateId, String comment) {
    return approveInternal(candidateId, comment, true);
  }

  private Map<String, Object> approveInternal(
      long candidateId, String comment, boolean refreshIndex) {
    Map<String, Object> candidate = findCandidateForUpdate(candidateId);
    if (!"pending_review".equals(candidate.get("status"))) {
      throw new IllegalStateException("只有审核中的候选词条可以批准");
    }
    ObjectNode publishDocument = buildPublishDocument(candidate);
    appendAiVariants(publishDocument, candidate);
    Map<String, Object> published =
        publishingService.publishCandidate(
            numberAsLong(candidate.get("duplicate_meme_id")),
            numberAsInt(candidate.get("review_base_version")),
            publishDocument,
            "候选词条审核发布: " + candidate.get("term_raw"),
            stringValue(candidate.get("submitted_by")),
            false);
    long publishedId = ((Number) published.get("id")).longValue();
    database.update(
        """
            UPDATE candidate_entries
            SET status = 'published', published_meme_id = ?, reviewed_by = ?,
                reviewed_at = ?, review_comment = ?
            WHERE id = ? AND status = 'pending_review'
            """,
        publishedId,
        actorProvider.currentActor(),
        java.time.LocalDateTime.now(),
        blankToNull(comment),
        candidateId);
    if (refreshIndex) publishingService.refreshRecognitionIndex(publishedId);
    return published;
  }

  public Map<String, Object> generateVariants(
      String term,
      String definition,
      List<com.vibelex.candidatediscovery.api.CandidateController.VariantRequest>
          retainedVariants) {
    validateCandidate(term, definition);
    List<Map<String, Object>> variants = new java.util.ArrayList<>();
    Set<String> retained = new java.util.HashSet<>();
    if (retainedVariants != null) {
      for (var item : retainedVariants) {
        if (item == null || item.variant() == null || item.variant().isBlank()) continue;
        String type =
            item.variantType() == null || item.variantType().isBlank()
                ? "alias"
                : item.variantType();
        retained.add(
            type
                + "\u0000"
                + normalizer.normalize(
                    item.variant().trim(), "zh-CN", normalizer.profileForVariant(type)));
      }
    }
    for (AiVariantGenerator.GeneratedVariant variant :
        variantGenerator.generate(term.trim(), definition.trim())) {
      if (normalizer
          .normalize(variant.variant(), "zh-CN")
          .equals(normalizer.normalize(term.trim(), "zh-CN"))) continue;
      String normalized =
          normalizer.normalize(
              variant.variant(), "zh-CN", normalizer.profileForVariant(variant.variantType()));
      String key = variant.variantType() + "\u0000" + normalized;
      if (!retained.add(key) || publishedVariantExists(normalized)) continue;
      variants.add(
          Map.of(
              "variant", variant.variant(),
              "variantType", variant.variantType(),
              "confidence", variant.confidence(),
              "sourceMethod", "ai_suggested",
              "evidence",
                  variant.evidence().stream()
                      .map(
                          item ->
                              Map.of(
                                  "url", item.url(),
                                  "title", item.title(),
                                  "snippet", item.snippet()))
                      .toList()));
    }
    return Map.of("variants", variants);
  }

  private boolean publishedVariantExists(String normalizedVariant) {
    Number count =
        (Number)
            database.scalar(
                """
                SELECT COUNT(*)
                FROM meme_entries e
                LEFT JOIN meme_variants v ON v.meme_id = e.id AND v.status = 'active'
                WHERE e.status = 'published'
                  AND (e.normalized_term = ? OR v.normalized_variant = ?)
                """,
                normalizedVariant,
                normalizedVariant);
    return count != null && count.longValue() > 0;
  }

  private void appendAiVariants(ObjectNode snapshot, Map<String, Object> candidate) {
    if (!variantGenerator.isEnabled()) return;
    try {
      ArrayNode variants = snapshot.withArray("variants");
      String canonical = snapshot.path("meme_entry").path("normalized_term").asString();
      Set<String> existing = variantKeys(variants);
      for (AiVariantGenerator.GeneratedVariant variant :
          variantGenerator.generate(
              String.valueOf(candidate.get("term_raw")),
              stringValue(candidate.get("definition_raw")))) {
        if (normalizer.normalize(variant.variant(), "zh-CN").equals(canonical)) continue;
        String normalized =
            normalizer.normalize(
                variant.variant(), "zh-CN", normalizer.profileForVariant(variant.variantType()));
        String key = variantKey(variant.variantType(), normalized);
        if (existing.add(key) && !publishedVariantExists(normalized)) {
          variants
              .addObject()
              .put("variant", variant.variant())
              .put("variant_type", variant.variantType())
              .put("confidence", variant.confidence())
              .put("source_method", "ai_suggested")
              .put("status", "active");
          appendVariantEvidence(snapshot.withArray("evidence"), variant);
        }
      }
    } catch (RuntimeException e) {
      log.warn("AI 变体生成失败；词条仍将按批准结果发布。candidateId={}", candidate.get("id"), e);
    }
  }

  private Map<String, Object> findCandidateForUpdate(long candidateId) {
    Map<String, Object> candidate =
        database.optionalOne(
            """
                SELECT c.*, CASE WHEN c.source_type = 'crawler' THEN COALESCE(JSON_UNQUOTE(JSON_EXTRACT(c.processing_note, '$.source_name')), JSON_UNQUOTE(JSON_EXTRACT(c.processing_note, '$.source_code')), '未知来源')
                                  ELSE COALESCE(r.source_name, '人工录入') END AS source_name,
                       r.source_url AS import_source_url
                FROM candidate_entries c
                LEFT JOIN source_import_runs r ON r.id = c.import_run_id
                WHERE c.id = ?
                FOR UPDATE
                """,
            candidateId);

    if (candidate == null) {
      throw new IllegalArgumentException("候选不存在");
    }
    return candidate;
  }

  private void ensureEditable(Map<String, Object> candidate) {
    if (!Set.of("editing", "returned").contains(String.valueOf(candidate.get("status")))) {
      throw new IllegalStateException("只有编辑中或已退回的候选词条可以修改");
    }
  }

  private ObjectNode buildPublishDocument(Map<String, Object> candidate) {
    String term = String.valueOf(candidate.get("term_raw"));
    String normalizedTerm = String.valueOf(candidate.get("normalized_term"));
    String definition = (String) candidate.get("definition_raw");
    JsonNode note = parseJson((String) candidate.get("processing_note"));
    Long duplicateMemeId = numberAsLong(candidate.get("duplicate_meme_id"));
    if (duplicateMemeId != null) {
      ObjectNode snapshot = existingSnapshot(duplicateMemeId);
      ObjectNode entry = (ObjectNode) snapshot.path("meme_entry");
      updateEntry(entry, term, normalizedTerm, note);
      ArrayNode senses = snapshot.withArray("senses");
      ObjectNode sense = senses.isEmpty() ? senses.addObject() : (ObjectNode) senses.get(0);
      updateSense(sense, definition);
      snapshot.remove("examples");
      appendExamples(snapshot, note);
      snapshot.remove("safety_policy");
      appendSafetyPolicy(snapshot, note);
      // A candidate that updates an existing entry is an edit, not a new import. Keep the
      // original discovery evidence instead of adding a misleading duplicate manual source.
      snapshot.remove("variants");
      appendVariants(snapshot, note);
      return snapshot;
    }

    ObjectNode snapshot = mapper.createObjectNode();
    snapshot.put("schema_version", "1.0");
    appendEntry(snapshot, term, normalizedTerm, note);
    appendSense(snapshot, definition);
    appendExamples(snapshot, note);
    appendDefaultMatchRule(snapshot, term);
    appendSafetyPolicy(snapshot, note);
    appendEvidence(snapshot, candidate);
    appendVariants(snapshot, note);
    return snapshot;
  }

  private void appendEntry(ObjectNode snapshot, String term, String normalizedTerm, JsonNode note) {
    ObjectNode entry = snapshot.putObject("meme_entry");
    updateEntry(entry, term, normalizedTerm, note);
    entry.put("language_code", "zh-CN");
    entry.put("trend_status", "untracked");
  }

  private void updateEntry(ObjectNode entry, String term, String normalizedTerm, JsonNode note) {
    entry.put("canonical_term", term);
    entry.put("normalized_term", normalizedTerm);
    entry.put("category", candidateCategory(note));
    entry.put("status", "published");

    if (note.path("source_tags").isArray()) {
      entry.set("domain_tags", note.path("source_tags").deepCopy());
    }

    if (note.hasNonNull("origin")) {
      entry.put("origin_summary", note.path("origin").asString());
    }
  }

  private void appendSense(ObjectNode snapshot, String definition) {
    ObjectNode sense = snapshot.putArray("senses").addObject();
    updateSense(sense, definition);
    sense.put("polarity", "neutral");
    sense.put("formality", "informal");
    sense.put("status", "active");
  }

  private void updateSense(ObjectNode sense, String definition) {
    String safeDefinition = definition == null || definition.isBlank() ? "待编辑补充释义" : definition;
    String shortDefinition = safeDefinition.substring(0, Math.min(500, safeDefinition.length()));

    sense.put("sense_no", 1);
    sense.put("short_definition", shortDefinition);
    sense.put("definition", safeDefinition);
  }

  private void appendVariants(ObjectNode snapshot, JsonNode note) {
    ArrayNode target = snapshot.withArray("variants");
    String canonical = snapshot.path("meme_entry").path("normalized_term").asString();
    Set<String> existing = variantKeys(target);
    for (JsonNode item : note.path("variants")) {
      String value = item.path("variant").asString().trim();
      String type = item.path("variant_type").asString().trim();
      if (value.isBlank() || type.isBlank()) continue;
      if (normalizer.normalize(value, "zh-CN").equals(canonical)) continue;
      String normalized = normalizer.normalize(value, "zh-CN", normalizer.profileForVariant(type));
      String key = variantKey(type, normalized);
      if ("ai_suggested".equals(item.path("source_method").asString())
          && publishedVariantExists(normalized)) continue;
      if (existing.add(key)) {
        target
            .addObject()
            .put("variant", value)
            .put("variant_type", type)
            .put("confidence", item.path("confidence").asDouble(1))
            .put("source_method", item.path("source_method").asString("editorial"))
            .put("status", "active");
        appendVariantEvidence(snapshot.withArray("evidence"), item);
      }
    }
  }

  private Set<String> variantKeys(ArrayNode variants) {
    Set<String> keys = new java.util.HashSet<>();
    for (JsonNode item : variants) {
      String value = item.path("variant").asString();
      String type = item.path("variant_type").asString();
      if (!value.isBlank() && !type.isBlank()) {
        String normalized =
            normalizer.normalize(value, "zh-CN", normalizer.profileForVariant(type));
        keys.add(variantKey(type, normalized));
      }
    }
    return keys;
  }

  private String variantKey(String type, String normalized) {
    return type + "\u0000" + normalized;
  }

  private void appendVariantEvidence(ArrayNode evidence, JsonNode variant) {
    Set<String> existingUrls = evidenceUrls(evidence);
    for (JsonNode item : variant.path("evidence")) {
      String url = item.path("url").asString().trim();
      if (url.isBlank() || url.length() > 2048 || !existingUrls.add(url)) continue;
      appendVariantEvidenceItem(
              evidence, item.path("title").asString("联网搜索"), url, item.path("snippet").asString())
          .put("confidence", variant.path("confidence").asDouble(1))
          .put("status", "active");
    }
  }

  private void appendVariantEvidence(
      ArrayNode evidence, AiVariantGenerator.GeneratedVariant variant) {
    Set<String> existingUrls = evidenceUrls(evidence);
    for (AiVariantGenerator.SearchEvidence item : variant.evidence()) {
      if (!existingUrls.add(item.url())) continue;
      appendVariantEvidenceItem(
              evidence, item.title().isBlank() ? "联网搜索" : item.title(), item.url(), item.snippet())
          .put("confidence", variant.confidence())
          .put("status", "active");
    }
  }

  private ObjectNode appendVariantEvidenceItem(
      ArrayNode evidence, String sourceName, String sourceUrl, String evidenceNote) {
    return evidence
        .addObject()
        .put("source_layer", "explanation")
        .put("source_name", sourceName)
        .put("source_url", sourceUrl)
        .put("evidence_role", "variant")
        .put("evidence_note", evidenceNote);
  }

  private Set<String> evidenceUrls(ArrayNode evidence) {
    Set<String> urls = new java.util.HashSet<>();
    for (JsonNode item : evidence) {
      String url = item.path("source_url").asString().trim();
      if (!url.isBlank()) urls.add(url);
    }
    return urls;
  }

  private void appendExamples(ObjectNode snapshot, JsonNode note) {
    ArrayNode examples = snapshot.putArray("examples");
    if (!note.path("examples").isArray()) {
      return;
    }

    note.path("examples")
        .forEach(
            example -> {
              if (example.isString()) {
                examples
                    .addObject()
                    .put("example_text", example.asString())
                    .put("example_role", "positive")
                    .put("status", "approved");
              }
            });
  }

  private void appendDefaultMatchRule(ObjectNode snapshot, String term) {
    snapshot
        .putArray("match_rules")
        .addObject()
        .put("rule_type", "normalized_match")
        .put("rule_value", term)
        .put("weight", 1.2)
        .put("priority", 100)
        .put("enabled", true);
  }

  private void appendSafetyPolicy(ObjectNode snapshot, JsonNode note) {
    boolean profanity = note.path("profanity").asBoolean(false);
    boolean offense = note.path("offense").asBoolean(false);
    boolean activeGeneration = !offense && !profanity;

    ObjectNode safety = snapshot.putObject("safety_policy");
    safety.put("profanity", profanity);
    safety.put("offense", offense);
    safety.put("risk_level", offense || profanity ? "medium" : "low");
    safety.put("detect_enabled", true);
    safety.put("display_enabled", true);
    safety.put("generate_enabled", activeGeneration);
    safety.put("recommend_enabled", activeGeneration);
    safety.put("moderation_policy", offense || profanity ? "manual_review" : "normal");
  }

  private void appendEvidence(ObjectNode snapshot, Map<String, Object> candidate) {
    appendEvidenceItem(snapshot.putArray("evidence"), candidate);
  }

  private void appendEvidenceItem(ArrayNode evidence, Map<String, Object> candidate) {
    boolean manual = "manual".equals(candidate.get("source_type"));
    String sourceUrl =
        candidate.get("source_url") == null
            ? stringValue(candidate.get("import_source_url"))
            : stringValue(candidate.get("source_url"));
    evidence
        .addObject()
        .put("source_layer", manual ? "internal" : "dataset")
        .put("source_name", String.valueOf(candidate.get("source_name")))
        .put("source_url", sourceUrl)
        .put("evidence_role", "discovery")
        .put("evidence_note", manual ? "管理台人工录入候选" : "导入候选，经人工编辑后提交审核")
        .put("status", "active");
  }

  private ObjectNode existingSnapshot(long memeId) {
    Object snapshotValue =
        database.scalar(
            """
                SELECT snapshot
                FROM meme_revisions
                WHERE meme_id = ?
                ORDER BY version DESC
                LIMIT 1
                """,
            memeId);
    if (snapshotValue == null) {
      throw new IllegalStateException("重复词条没有可编辑版本快照");
    }

    try {
      return (ObjectNode) mapper.readTree(String.valueOf(snapshotValue));
    } catch (Exception exception) {
      throw new IllegalStateException("版本快照无效", exception);
    }
  }

  private JsonNode parseJson(String json) {
    if (json == null) {
      return mapper.createObjectNode();
    }
    try {
      return mapper.readTree(json);
    } catch (Exception ignored) {
      return mapper.createObjectNode();
    }
  }

  private String toJson(JsonNode node) {
    try {
      return mapper.writeValueAsString(node);
    } catch (Exception exception) {
      throw new IllegalStateException("无法序列化候选快照", exception);
    }
  }

  private String editorNote(
      String currentJson,
      String term,
      String category,
      String origin,
      List<String> examples,
      boolean profanity,
      boolean offense,
      List<com.vibelex.candidatediscovery.api.CandidateController.VariantRequest> variants) {
    ObjectNode note =
        parseJson(currentJson) instanceof ObjectNode objectNode
            ? objectNode
            : mapper.createObjectNode();
    note.put("category", category == null || category.isBlank() ? "other" : category.trim());
    if (origin == null || origin.isBlank()) {
      note.remove("origin");
    } else {
      note.put("origin", origin.trim());
    }
    ArrayNode values = note.putArray("examples");
    if (examples != null) {
      examples.stream()
          .filter(example -> example != null && !example.isBlank())
          .map(String::trim)
          .forEach(values::add);
    }
    note.put("profanity", profanity);
    note.put("offense", offense);
    ArrayNode savedVariants = note.putArray("variants");
    if (variants != null) {
      String normalizedTerm = normalizer.normalize(term, "zh-CN");
      variants.stream()
          .filter(
              variant ->
                  variant != null && variant.variant() != null && !variant.variant().isBlank())
          .forEach(
              variant -> {
                if (normalizer
                    .normalize(variant.variant().trim(), "zh-CN")
                    .equals(normalizedTerm)) {
                  if (!"ai_suggested".equals(variant.sourceMethod())) {
                    throw new IllegalArgumentException("词形变体不能与候选词形重复");
                  }
                  return;
                }
                String type =
                    variant.variantType() == null || variant.variantType().isBlank()
                        ? "alias"
                        : variant.variantType().trim();
                ObjectNode saved =
                    savedVariants
                        .addObject()
                        .put("variant", variant.variant().trim())
                        .put("variant_type", type)
                        .put("confidence", variant.confidence() == null ? 1 : variant.confidence())
                        .put(
                            "source_method",
                            "ai_suggested".equals(variant.sourceMethod())
                                ? "ai_suggested"
                                : "editorial");
                ArrayNode evidence = saved.putArray("evidence");
                if (variant.evidence() != null) {
                  Set<String> evidenceUrls = new java.util.HashSet<>();
                  variant.evidence().stream()
                      .filter(item -> item != null && item.url() != null && !item.url().isBlank())
                      .filter(item -> evidenceUrls.add(item.url().trim()))
                      .limit(2)
                      .forEach(
                          item ->
                              evidence
                                  .addObject()
                                  .put("url", item.url().trim())
                                  .put("title", item.title() == null ? "" : item.title().trim())
                                  .put(
                                      "snippet",
                                      item.snippet() == null ? "" : item.snippet().trim()));
                }
              });
    }
    return toJson(note);
  }

  private String candidateCategory(JsonNode note) {
    if (note.hasNonNull("category") && !note.path("category").asString().isBlank()) {
      return note.path("category").asString();
    }
    return category(note.path("type_en").asString());
  }

  private void validateCandidate(String term, String definition) {
    if (term == null || term.isBlank()) {
      throw new IllegalArgumentException("候选词形不能为空");
    }
    if (definition == null || definition.isBlank()) {
      throw new IllegalArgumentException("候选释义不能为空");
    }
    if (term.trim().length() > 255) {
      throw new IllegalArgumentException("候选词形不能超过 255 个字符");
    }
  }

  private String crawlerRecordKey(String sourceCode, String sourceRecordKey) {
    try {
      String digest =
          HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(
                          (sourceCode + "\n" + sourceRecordKey).getBytes(StandardCharsets.UTF_8)));
      return "crawler:" + sourceCode + ":" + digest;
    } catch (Exception e) {
      throw new IllegalStateException("无法生成爬虫来源键", e);
    }
  }

  private void appendCrawlerStrings(
      ArrayNode target, List<String> values, int maximumItems, int maximumLength) {
    if (values == null) return;
    values.stream()
        .filter(Objects::nonNull)
        .map(String::trim)
        .filter(value -> !value.isBlank())
        .map(value -> value.substring(0, Math.min(maximumLength, value.length())))
        .distinct()
        .limit(maximumItems)
        .forEach(target::add);
  }

  private String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private String stringValue(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  private List<Long> validateBatchIds(List<Long> candidateIds, String action) {
    if (candidateIds == null || candidateIds.isEmpty()) {
      throw new IllegalArgumentException("请选择需要" + action + "的候选词条");
    }
    List<Long> ids = candidateIds.stream().filter(Objects::nonNull).distinct().toList();
    if (ids.isEmpty()) {
      throw new IllegalArgumentException("请选择需要" + action + "的候选词条");
    }
    if (ids.size() > 100) {
      throw new IllegalArgumentException("单次最多处理 100 条候选词条");
    }
    return ids;
  }

  private Long findDuplicate(String normalizedTerm) {
    return numberAsLong(
        database.scalar(
            "SELECT id FROM meme_entries WHERE normalized_term = ? AND language_code = 'zh-CN' LIMIT 1",
            normalizedTerm));
  }

  private String category(String type) {
    String value = type.toLowerCase(Locale.ROOT);
    if (value.contains("homophone")
        || value.contains("homophonic")
        || value.contains("homophony")) {
      return "homophone";
    }
    if (value.contains("abbrev")) {
      return "abbreviation";
    }
    if (value.contains("template")) {
      return "template_phrase";
    }
    return "other";
  }

  private Long numberAsLong(Object value) {
    return value instanceof Number number ? number.longValue() : null;
  }

  private Integer numberAsInt(Object value) {
    return value instanceof Number number ? number.intValue() : null;
  }
}
