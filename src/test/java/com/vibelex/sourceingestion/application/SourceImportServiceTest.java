package com.vibelex.sourceingestion.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vibelex.candidatediscovery.domain.TermNormalizer;
import com.vibelex.shared.persistence.MyBatisDatabase;
import com.vibelex.sourceingestion.api.ImportController.ImportRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class SourceImportServiceTest {
  @TempDir Path tempDir;

  @Test
  void plansAllRecordsBeforeMarkingTheRunAsRunning() throws Exception {
    Path file = tempDir.resolve("Buzzword.json");
    Files.writeString(file, "[]");
    RecordingDatabase database = new RecordingDatabase();
    SourceImportService service = service(database, file);

    Map<String, Object> result =
        service.importFile(
            "buzzword",
            new ImportRequest(
                file.getFileName().toString(),
                "v1",
                null,
                "approved",
                null,
                "reviewed",
                "tester"));

    assertThat(result.get("reused")).isEqualTo(false);
    assertThat(database.inserts.get(0).sql()).contains("status").contains("'planning'");
    assertThat(database.inserts.get(1).sql()).contains("source_import_records");
    assertThat(database.updates).anyMatch(call -> call.sql().contains("status='running'"));
  }

  @Test
  void manualRetryStartsANewAttemptBudget() throws Exception {
    RecordingDatabase database = new RecordingDatabase();
    SourceImportService service = service(database, tempDir.resolve("Buzzword.json"));

    service.retryFailed(9L, 12L);

    assertThat(database.updates.get(0).sql())
        .contains("attempt_count=0")
        .contains("status='failed'");
  }

  @Test
  void cancelsARunningImportWithoutChangingItsRecords() {
    RecordingDatabase database = new RecordingDatabase();
    SourceImportService service = service(database, tempDir.resolve("Buzzword.json"));

    Map<String, Object> result = service.cancel(9L);

    assertThat(result.get("status")).isEqualTo("cancelled");
    assertThat(database.updates).hasSize(1);
    assertThat(database.updates.get(0).sql())
        .contains("status='cancelled'")
        .contains("status='running'")
        .contains("updated_at=NOW(3)");
  }

  @Test
  void cancellingAnAlreadyCancelledImportIsIdempotent() {
    RecordingDatabase database = new RecordingDatabase();
    database.runStatus = "cancelled";
    SourceImportService service = service(database, tempDir.resolve("Buzzword.json"));

    Map<String, Object> result = service.cancel(9L);

    assertThat(result.get("status")).isEqualTo("cancelled");
    assertThat(database.updates).isEmpty();
  }

  @Test
  void cancelledImportCannotRetryFailedRecords() {
    RecordingDatabase database = new RecordingDatabase();
    database.runStatus = "cancelled";
    SourceImportService service = service(database, tempDir.resolve("Buzzword.json"));

    assertThatThrownBy(() -> service.retryFailed(9L, null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("已停止");
    assertThat(database.updates).isEmpty();
  }

  @Test
  void runListIncludesUpdatedAt() {
    RecordingDatabase database = new RecordingDatabase();
    SourceImportService service = service(database, tempDir.resolve("Buzzword.json"));

    service.runs();

    assertThat(database.lists.get(0).sql()).contains("updated_at");
  }

  private SourceImportService service(RecordingDatabase database, Path file) {
    CandidateImporter importer =
        new CandidateImporter() {
          public String sourceCode() {
            return "buzzword";
          }

          public String sourceName() {
            return "Buzzword";
          }

          public String sourceUrl() {
            return "https://example.com/dataset";
          }

          public String parserVersion() {
            return "test-v1";
          }

          public boolean supportsFileName(String name) {
            return "Buzzword.json".equalsIgnoreCase(name);
          }

          public ImportedBatch parse(Path ignored) {
            return new ImportedBatch(
                1,
                0,
                List.of(
                    new ImportedCandidate(
                        0,
                        "破防",
                        "破防",
                        "情绪受到强烈触动",
                        sourceUrl(),
                        Map.of("examples", List.of("我破防了")))),
                List.of());
          }
        };
    return new SourceImportService(
        database,
        new ObjectMapper(),
        new TermNormalizer(),
        () -> "tester",
        List.of(importer),
        List.of(),
        file.getParent().toString(),
        1024 * 1024);
  }

  private static class RecordingDatabase extends MyBatisDatabase {
    private final List<Call> inserts = new ArrayList<>();
    private final List<Call> updates = new ArrayList<>();
    private final List<Call> lists = new ArrayList<>();
    private long nextId = 1;
    private String runStatus = "running";

    RecordingDatabase() {
      super(null);
    }

    @Override
    public List<Map<String, Object>> list(String sql, Object... args) {
      lists.add(new Call(sql, args));
      return List.of();
    }

    @Override
    public long insert(String sql, Object... args) {
      inserts.add(new Call(sql, args));
      return nextId++;
    }

    @Override
    public int update(String sql, Object... args) {
      updates.add(new Call(sql, args));
      if (sql.contains("status='cancelled'")) runStatus = "cancelled";
      return 1;
    }

    @Override
    public Map<String, Object> one(String sql, Object... args) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("id", 1L);
      row.put("status", runStatus);
      return row;
    }
  }

  private record Call(String sql, Object[] args) {}
}
