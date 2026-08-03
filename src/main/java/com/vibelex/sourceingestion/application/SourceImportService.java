package com.vibelex.sourceingestion.application;

import com.vibelex.actorcontext.CurrentActorProvider;
import com.vibelex.candidatediscovery.domain.TermNormalizer;
import com.vibelex.crawling.CrawlConnector;
import com.vibelex.shared.persistence.MyBatisDatabase;
import com.vibelex.sourceingestion.api.ImportController.ImportRequest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class SourceImportService {
  private final MyBatisDatabase database;
  private final ObjectMapper mapper;
  private final TermNormalizer normalizer;
  private final CurrentActorProvider actorProvider;
  private final Path dataDirectory;
  private final long maxFileBytes;
  private final Map<String, CandidateImporter> importers;
  private final List<String> crawlerSourceNames;

  public SourceImportService(
      MyBatisDatabase database,
      ObjectMapper mapper,
      TermNormalizer normalizer,
      CurrentActorProvider actorProvider,
      List<CandidateImporter> importers,
      List<CrawlConnector> crawlConnectors,
      @Value("${vibelex.data-directory}") String dataDirectory,
      @Value("${vibelex.import.max-file-bytes}") long maxFileBytes) {
    this.database = database;
    this.mapper = mapper;
    this.normalizer = normalizer;
    this.actorProvider = actorProvider;
    this.dataDirectory = Paths.get(dataDirectory).toAbsolutePath().normalize();
    this.maxFileBytes = maxFileBytes;
    Map<String, CandidateImporter> registry = new HashMap<>();
    for (CandidateImporter importer : importers) registry.put(importer.sourceCode(), importer);
    this.importers = Map.copyOf(registry);
    this.crawlerSourceNames =
        crawlConnectors.stream().map(CrawlConnector::sourceName).sorted().toList();
  }

  public List<Map<String, String>> sources() {
    return importers.values().stream()
        .sorted(Comparator.comparing(CandidateImporter::sourceName))
        .map(
            importer ->
                Map.of(
                    "code", importer.sourceCode(),
                    "name", importer.sourceName(),
                    "parserVersion", importer.parserVersion()))
        .toList();
  }

  /** Shared source dictionary for candidate, review, and published-entry filtering. */
  public List<String> sourceDictionary() {
    return Stream.concat(Stream.of("Buzzword", "CHIME", "人工录入"), crawlerSourceNames.stream())
        .distinct()
        .toList();
  }

  public List<String> availableFiles(String sourceCode) {
    CandidateImporter importer = importer(sourceCode);
    if (!Files.isDirectory(dataDirectory)) return List.of();
    try (Stream<Path> files = Files.list(dataDirectory)) {
      return files
          .filter(Files::isRegularFile)
          .filter(path -> importer.supportsFileName(path.getFileName().toString()))
          .map(path -> path.getFileName().toString())
          .sorted()
          .toList();
    } catch (IOException e) {
      throw new IllegalStateException("无法读取 data 目录", e);
    }
  }

  public List<Map<String, Object>> runs() {
    return database.list(
        """
                SELECT id, source_code, source_name, source_version, file_name, file_hash,
                       attempt_no, parser_version, license_status, status,
                       total_count, accepted_count, rejected_count,
                       candidate_count, imported_count, duplicate_count,
                       ignored_count, failed_count, error_summary, initiated_by,
                       started_at, finished_at, updated_at
                FROM source_import_runs
                ORDER BY id DESC
                LIMIT 100
                """);
  }

  public Map<String, Object> summary() {
    return database.one(
        """
        SELECT COUNT(*) AS run_count,
               COALESCE(SUM(candidate_count), 0) AS candidate_count,
               COALESCE(SUM(failed_count), 0) AS failed_count,
               MAX(COALESCE(finished_at, started_at)) AS last_import_at
        FROM source_import_runs
        """);
  }

  public Map<String, Object> importFile(String sourceCode, ImportRequest request) {
    CandidateImporter importer = importer(sourceCode);
    Path file = resolveFile(request.fileName());
    validateFile(file, importer);
    String sourceVersion = valueOr(request.sourceVersion(), "manual-local");
    String licenseStatus = valueOr(request.licenseStatus(), "pending");
    String checkedBy = valueOr(request.licenseCheckedBy(), "system");
    if (!"approved".equals(licenseStatus))
      throw new IllegalArgumentException("license_status 必须为 approved 才能导入");
    String hash = sha256(file);
    String fingerprint = sha256(importer.sourceName() + "\n" + sourceVersion + "\n" + hash);
    List<Map<String, Object>> prior =
        database.list(
            "SELECT * FROM source_import_runs WHERE import_fingerprint = ? ORDER BY attempt_no DESC",
            fingerprint);
    Optional<Map<String, Object>> reusable =
        prior.stream()
            .filter(
                run ->
                    Set.of("running", "succeeded", "partial_success")
                        .contains(String.valueOf(run.get("status")))
                        || "planning".equals(String.valueOf(run.get("status"))))
            .findFirst();
    if (reusable.isPresent()) {
      Map<String, Object> result = new java.util.LinkedHashMap<>(reusable.get());
      result.put("reused", true);
      return result;
    }
    int attempt = prior.isEmpty() ? 1 : ((Number) prior.get(0).get("attempt_no")).intValue() + 1;
    long runId =
        createRun(
            importer,
            request,
            sourceVersion,
            licenseStatus,
            checkedBy,
            file,
            hash,
            fingerprint,
            attempt);
    try {
      CandidateImporter.ImportedBatch batch = importer.parse(file);
      for (CandidateImporter.ImportedCandidate item : batch.candidates()) {
        String normalized = normalizer.normalize(item.term(), "zh-CN");
        String recordKey =
            item.sourceRecordKey() == null || item.sourceRecordKey().isBlank()
                ? sha256(item.sourceIndex() + "\n" + normalized)
                : item.sourceRecordKey();
        database.insert(
            """
            INSERT INTO source_import_records(
                import_run_id, source_index, source_record_key, term_raw,
                normalized_term, definition_raw, source_url, parser_version,
                processing_note, status
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'pending')
            """,
            runId,
            item.sourceIndex(),
            recordKey,
            item.term(),
            normalized,
            item.definition(),
            valueOr(item.sourceUrl(), importer.sourceUrl()),
            importer.parserVersion(),
            processingNote(item.processingNote()));
      }
      for (String error : batch.errors()) createIgnoredRecord(runId, importer, error);
      database.update(
          """
          UPDATE source_import_runs
          SET total_count=?, accepted_count=?, rejected_count=?, ignored_count=?,
              error_summary=?, status='running'
          WHERE id=?
          """,
          batch.totalCount(),
          batch.totalCount() - batch.rejectedCount(),
          batch.rejectedCount(),
          batch.rejectedCount(),
          summarize(batch.errors()),
          runId);
    } catch (RuntimeException e) {
      finishRun(runId, "failed", 0, 0, 1, 0, e.getMessage());
      throw e;
    }
    Map<String, Object> result =
        new java.util.LinkedHashMap<>(
            database.one("SELECT * FROM source_import_runs WHERE id = ?", runId));
    result.put("reused", false);
    return result;
  }

  public Map<String, Object> records(long runId, String status, String query, int page, int size) {
    database.one("SELECT id FROM source_import_runs WHERE id=?", runId);
    int safePage = Math.max(1, page);
    int safeSize = Math.max(10, Math.min(100, size));
    String selectedStatus = status == null || status.isBlank() ? "all" : status;
    StringBuilder where = new StringBuilder(" WHERE r.import_run_id=?");
    List<Object> args = new java.util.ArrayList<>();
    args.add(runId);
    if ("queued".equals(selectedStatus)) {
      where.append(" AND r.status IN ('pending','processing')");
    } else if (!"all".equals(selectedStatus)) {
      if (!Set.of("imported", "duplicate", "ignored", "failed").contains(selectedStatus))
        throw new IllegalArgumentException("不支持的导入记录状态: " + selectedStatus);
      where.append(" AND r.status=?");
      args.add(selectedStatus);
    }
    if (query != null && !query.isBlank()) {
      where.append(" AND (r.term_raw LIKE ? OR r.normalized_term LIKE ?)");
      String like = "%" + query.trim() + "%";
      args.add(like);
      args.add(like);
    }
    long total =
        ((Number)
                database.scalar(
                    "SELECT COUNT(*) FROM source_import_records r" + where, args.toArray()))
            .longValue();
    args.add(safeSize);
    args.add((safePage - 1L) * safeSize);
    List<Map<String, Object>> items =
        database.list(
            """
            SELECT r.id, r.import_run_id, r.source_index, r.source_record_key,
                   r.term_raw, r.normalized_term, r.status, r.candidate_id,
                   r.duplicate_target_type, r.duplicate_target_id,
                   r.attempt_count, r.processor_stage, r.error_type,
                   r.error_message, r.ai_provider, r.ai_model,
                   r.processor_version, r.processed_at, r.created_at, r.updated_at,
                   c.status AS candidate_status
            FROM source_import_records r
            LEFT JOIN candidate_entries c ON c.id=r.candidate_id
            """
                + where
                + " ORDER BY r.source_index, r.id LIMIT ? OFFSET ?",
            args.toArray());
    return Map.of(
        "items", items,
        "page", safePage,
        "size", safeSize,
        "totalElements", total,
        "totalPages", total == 0 ? 0 : (total + safeSize - 1) / safeSize);
  }

  public Map<String, Object> record(long runId, long recordId) {
    return database.one(
        """
        SELECT r.*, c.status AS candidate_status
        FROM source_import_records r
        LEFT JOIN candidate_entries c ON c.id=r.candidate_id
        WHERE r.import_run_id=? AND r.id=?
        """,
        runId,
        recordId);
  }

  public int retryFailed(long runId, Long recordId) {
    Map<String, Object> run =
        database.one("SELECT id, status FROM source_import_runs WHERE id=?", runId);
    if ("cancelled".equals(String.valueOf(run.get("status"))))
      throw new IllegalStateException("已停止的导入任务不能重试");
    String condition = recordId == null ? "" : " AND id=?";
    Object[] args = recordId == null ? new Object[] {runId} : new Object[] {runId, recordId};
    int changed =
        database.update(
            """
            UPDATE source_import_records
            SET status='pending', next_attempt_at=NULL, lease_owner=NULL, lease_until=NULL,
                attempt_count=0, processor_stage=NULL, error_type=NULL, error_message=NULL,
                processed_at=NULL
            WHERE import_run_id=? AND status='failed'
            """
                + condition,
            args);
    if (changed == 0 && recordId != null) throw new IllegalArgumentException("没有可重试的失败词条");
    if (changed > 0) {
      database.update(
          """
          UPDATE source_import_runs
          SET status='running', failed_count=GREATEST(0, failed_count-?), finished_at=NULL
          WHERE id=? AND status<>'cancelled'
          """,
          changed,
          runId);
    }
    return changed;
  }

  public Map<String, Object> cancel(long runId) {
    Map<String, Object> run =
        database.one("SELECT * FROM source_import_runs WHERE id=?", runId);
    String status = String.valueOf(run.get("status"));
    if ("cancelled".equals(status)) return run;
    if (!"running".equals(status)) throw new IllegalStateException("只有运行中的导入任务可以停止");

    int changed =
        database.update(
            """
            UPDATE source_import_runs
            SET status='cancelled', finished_at=NOW(3), updated_at=NOW(3),
                error_summary='任务已由管理员停止'
            WHERE id=? AND status='running'
            """,
            runId);
    Map<String, Object> result =
        database.one("SELECT * FROM source_import_runs WHERE id=?", runId);
    if (changed == 0 && !"cancelled".equals(String.valueOf(result.get("status"))))
      throw new IllegalStateException("任务状态已变化，请刷新后重试");
    return result;
  }

  private CandidateImporter importer(String sourceCode) {
    CandidateImporter importer = importers.get(sourceCode);
    if (importer == null) throw new IllegalArgumentException("不支持的数据来源: " + sourceCode);
    return importer;
  }

  private long createRun(
      CandidateImporter importer,
      ImportRequest request,
      String version,
      String licenseStatus,
      String checkedBy,
      Path file,
      String hash,
      String fingerprint,
      int attempt) {
    return database.insert(
        """
        INSERT INTO source_import_runs(
            source_code, source_name, source_url, source_version, source_commit,
            file_name, file_hash, import_fingerprint, attempt_no,
            parser_version, license_status, license_snapshot,
            upstream_rights_note, license_checked_by, initiated_by, status
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'planning')
        """,
        importer.sourceCode(),
        importer.sourceName(),
        importer.sourceUrl(),
        version,
        request.sourceCommit(),
        file.getFileName().toString(),
        hash,
        fingerprint,
        attempt,
        importer.parserVersion(),
        licenseStatus,
        request.licenseSnapshot(),
        valueOr(request.upstreamRightsNote(), "Manual import rights reviewed"),
        checkedBy,
        actorProvider.currentActor());
  }

  private void finishRun(
      long id,
      String status,
      int total,
      int accepted,
      int rejected,
      int candidates,
      String errors) {
    database.update(
        """
        UPDATE source_import_runs
        SET status = ?, total_count = ?, accepted_count = ?, rejected_count = ?,
            candidate_count = ?, failed_count = CASE WHEN ?='failed' THEN 1 ELSE failed_count END,
            error_summary = ?, finished_at = ?
        WHERE id = ?
        """,
        status,
        total,
        accepted,
        rejected,
        candidates,
        status,
        errors == null || errors.isBlank() ? null : errors,
        LocalDateTime.now(),
        id);
  }

  private void createIgnoredRecord(
      long runId, CandidateImporter importer, String parserError) {
    int index = errorIndex(parserError);
    database.insert(
        """
        INSERT INTO source_import_records(
            import_run_id, source_index, source_record_key, term_raw,
            parser_version, status, processor_stage, error_type, error_message, processed_at
        ) VALUES (?, ?, ?, ?, ?, 'ignored', 'parse', 'InvalidSourceRecord', ?, NOW(3))
        """,
        runId,
        Math.max(0, index),
        "rejected:" + Math.max(0, index),
        index >= 0 ? "#" + index : "无法解析的来源记录",
        importer.parserVersion(),
        clip(parserError, 2000));
  }

  private int errorIndex(String error) {
    if (error == null || !error.startsWith("#")) return -1;
    int colon = error.indexOf(':');
    if (colon < 2) return -1;
    try {
      return Integer.parseInt(error.substring(1, colon));
    } catch (NumberFormatException ignored) {
      return -1;
    }
  }

  private String summarize(List<String> errors) {
    if (errors == null || errors.isEmpty()) return null;
    return clip(String.join("; ", errors.stream().limit(10).toList()), 2000);
  }

  private String clip(String value, int maximum) {
    if (value == null || value.length() <= maximum) return value;
    return value.substring(0, maximum);
  }

  private Path resolveFile(String name) {
    Path path = dataDirectory.resolve(name).normalize();
    if (!path.startsWith(dataDirectory)) throw new IllegalArgumentException("文件必须位于 data 目录");
    return path;
  }

  private void validateFile(Path file, CandidateImporter importer) {
    try {
      if (!Files.isRegularFile(file)) throw new IllegalArgumentException("文件不存在");
      if (!importer.supportsFileName(file.getFileName().toString()))
        throw new IllegalArgumentException(importer.sourceName() + " 不支持该文件格式");
      if (Files.size(file) > maxFileBytes) throw new IllegalArgumentException("文件超过大小限制");
    } catch (IOException e) {
      throw new IllegalStateException("无法校验文件", e);
    }
  }

  private String processingNote(Map<String, Object> note) {
    try {
      return mapper.writeValueAsString(note == null ? Map.of() : note);
    } catch (Exception e) {
      throw new IllegalArgumentException("无法保存来源处理说明", e);
    }
  }

  private String valueOr(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value.trim();
  }

  private String sha256(Path file) {
    try (InputStream input = Files.newInputStream(file)) {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] buffer = new byte[8192];
      for (int read; (read = input.read(buffer)) >= 0; ) digest.update(buffer, 0, read);
      return HexFormat.of().formatHex(digest.digest());
    } catch (Exception e) {
      throw new IllegalStateException("无法计算文件哈希", e);
    }
  }

  private String sha256(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
