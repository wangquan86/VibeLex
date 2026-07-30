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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
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
                SELECT id, source_name, source_version, file_name, file_hash,
                       attempt_no, parser_version, license_status, status,
                       total_count, accepted_count, rejected_count,
                       candidate_count, error_summary, initiated_by,
                       started_at, finished_at
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
               COALESCE(SUM(rejected_count), 0) AS failed_count,
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
                        .contains(String.valueOf(run.get("status"))))
            .findFirst();
    if (reusable.isPresent()) return reusable.get();
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
    int accepted = 0;
    int candidates = 0;
    CandidateImporter.ImportedBatch batch;
    try {
      batch = importer.parse(file);
      accepted = batch.totalCount() - batch.rejectedCount();
      int rejected = batch.rejectedCount();
      List<String> errors = new ArrayList<>(batch.errors());
      int skippedDuplicates = 0;
      for (CandidateImporter.ImportedCandidate item : batch.candidates()) {
        try {
          String normalized = normalizer.normalize(item.term(), "zh-CN");
          if (candidateExists(normalized)) {
            skippedDuplicates++;
            continue;
          }
          String recordKey =
              item.sourceRecordKey() == null || item.sourceRecordKey().isBlank()
                  ? sha256(item.sourceIndex() + "\n" + normalized)
                  : item.sourceRecordKey();
          database.insert(
              """
              INSERT INTO candidate_entries(
                  import_run_id, import_fingerprint, source_record_key,
                  term_raw, normalized_term, definition_raw, source_url,
                  parser_version, source_type, created_by, status,
                  duplicate_meme_id, processing_note
              ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'import', ?, 'editing', ?, ?)
              """,
              runId,
              fingerprint,
              recordKey,
              item.term(),
              normalized,
              item.definition(),
              valueOr(item.sourceUrl(), importer.sourceUrl()),
              importer.parserVersion(),
              actorProvider.currentActor(),
              findDuplicate(normalized),
              processingNote(item.processingNote()));
          candidates++;
        } catch (DuplicateKeyException e) {
          // The source row is accepted but already exists for this import fingerprint.
        } catch (RuntimeException e) {
          accepted--;
          rejected++;
          if (errors.size() < 10) errors.add("#" + item.sourceIndex() + ": " + e.getMessage());
        }
      }
      if (skippedDuplicates > 0) errors.add("已跳过 " + skippedDuplicates + " 条重复词条");
      String status = rejected == 0 ? "succeeded" : accepted > 0 ? "partial_success" : "failed";
      finishRun(
          runId,
          status,
          batch.totalCount(),
          accepted,
          rejected,
          candidates,
          String.join("; ", errors));
    } catch (RuntimeException e) {
      finishRun(runId, "failed", 0, 0, 1, 0, e.getMessage());
      throw e;
    }
    return database.one("SELECT * FROM source_import_runs WHERE id = ?", runId);
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
            source_name, source_url, source_version, source_commit,
            file_name, file_hash, import_fingerprint, attempt_no,
            parser_version, license_status, license_snapshot,
            upstream_rights_note, license_checked_by, initiated_by
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
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
            candidate_count = ?, error_summary = ?, finished_at = ?
        WHERE id = ?
        """,
        status,
        total,
        accepted,
        rejected,
        candidates,
        errors == null || errors.isBlank() ? null : errors,
        LocalDateTime.now(),
        id);
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

  private Long findDuplicate(String normalized) {
    Object id =
        database.scalar(
            "SELECT id FROM meme_entries WHERE normalized_term = ? AND language_code = 'zh-CN' LIMIT 1",
            normalized);
    return id == null ? null : ((Number) id).longValue();
  }

  private boolean candidateExists(String normalized) {
    return findDuplicate(normalized) != null
        || database.scalar(
                "SELECT id FROM candidate_entries WHERE normalized_term = ? LIMIT 1", normalized)
            != null;
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
