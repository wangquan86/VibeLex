package com.vibelex.crawling;

import com.vibelex.crawling.CrawlConnector.CrawlPointer;
import com.vibelex.crawling.CrawlConnector.EnumerationResult;
import com.vibelex.shared.persistence.MyBatisDatabase;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class CrawlExecutionService {
  private final MyBatisDatabase database;
  private final ObjectMapper mapper;
  private final CrawlProperties properties;
  private final Map<String, CrawlConnector> connectors;

  public CrawlExecutionService(
      MyBatisDatabase database,
      ObjectMapper mapper,
      CrawlProperties properties,
      List<CrawlConnector> connectors) {
    this.database = database;
    this.mapper = mapper;
    this.properties = properties;
    this.connectors =
        connectors.stream()
            .collect(Collectors.toUnmodifiableMap(CrawlConnector::sourceCode, Function.identity()));
  }

  public List<Map<String, Object>> sources() {
    for (CrawlConnector connector : connectors.values()) ensureCheckpoint(connector.sourceCode());
    return connectors.values().stream()
        .sorted(Comparator.comparing(CrawlConnector::sourceCode))
        .map(
            connector -> {
              Map<String, Object> row =
                  new LinkedHashMap<>(
                      database.one(
                          "SELECT * FROM crawl_checkpoints WHERE source_code = ?",
                          connector.sourceCode()));
              row.put("source_name", connector.sourceName());
              row.put("enabled", sourceEnabled(connector.sourceCode()));
              row.put("record_summary", recordSummary(connector.sourceCode()));
              return row;
            })
        .toList();
  }

  public Map<String, Object> source(String sourceCode) {
    connector(sourceCode);
    ensureCheckpoint(sourceCode);
    Map<String, Object> row =
        new LinkedHashMap<>(
            database.one("SELECT * FROM crawl_checkpoints WHERE source_code = ?", sourceCode));
    row.put("source_name", connector(sourceCode).sourceName());
    row.put("enabled", sourceEnabled(sourceCode));
    row.put("record_summary", recordSummary(sourceCode));
    return row;
  }

  private List<Map<String, Object>> recordSummary(String sourceCode) {
    return database.list(
        "SELECT status, COUNT(*) AS count FROM crawl_records WHERE source_code = ? GROUP BY status",
        sourceCode);
  }

  public synchronized Map<String, Object> startSync(String sourceCode) {
    CrawlConnector connector = connector(sourceCode);
    ensureEnabled(sourceCode);
    ensureCheckpoint(sourceCode);
    int claimed =
        database.update(
            """
            UPDATE crawl_checkpoints
            SET current_status='planning', pending_checkpoint=NULL,
                discovered_count=0, imported_count=0, duplicate_count=0,
                ignored_count=0, failed_count=0, error_summary=NULL,
                started_at=NOW(3), finished_at=NULL
            WHERE source_code=? AND current_status IN ('idle', 'partial', 'failed')
            """,
            sourceCode);
    if (claimed == 0) {
      throw new IllegalStateException("该来源正在同步，请勿重复启动");
    }

    try {
      database.update(
          """
          UPDATE crawl_records
          SET status='pending', attempt_count=0, next_attempt_at=NOW(3),
              lease_owner=NULL, lease_until=NULL, error_type=NULL, error_message=NULL
          WHERE source_code=? AND status='failed'
          """,
          sourceCode);
      JsonNode checkpoint = checkpoint(sourceCode);
      EnumerationResult result = connector.enumerate(checkpoint);
      boolean checkpointChanged = !java.util.Objects.equals(checkpoint, result.nextCheckpoint());
      for (CrawlPointer pointer : result.items()) {
        database.update(
            """
            INSERT IGNORE INTO crawl_records(
                source_code, source_record_key, source_url, source_modified_at,
                status, attempt_count, next_attempt_at
            ) VALUES (?, ?, ?, ?, 'pending', 0, NOW(3))
            """,
            sourceCode,
            pointer.sourceRecordKey(),
            pointer.sourceUrl(),
            LocalDateTime.ofInstant(pointer.sourceModifiedAt(), ZoneOffset.UTC));
      }
      Number queued =
          (Number)
              database.scalar(
                  """
                  SELECT COUNT(*) FROM crawl_records
                  WHERE source_code=? AND status IN ('pending', 'processing', 'retry_wait')
                  """,
                  sourceCode);
      long queuedCount = queued == null ? 0 : queued.longValue();
      database.update(
          """
          UPDATE crawl_checkpoints
          SET current_status='running', discovered_count=?, pending_checkpoint=?
          WHERE source_code=? AND current_status='planning'
          """,
          queuedCount,
          json(result.nextCheckpoint()),
          sourceCode);
      finishIfComplete(sourceCode);
      Map<String, Object> response = source(sourceCode);
      response.put(
          "sync_outcome",
          queuedCount > 0 ? "started" : checkpointChanged ? "checkpoint_updated" : "no_change");
      response.put("queued_count", queuedCount);
      return response;
    } catch (RuntimeException e) {
      database.update(
          """
          UPDATE crawl_checkpoints
          SET current_status='failed', error_summary=?, finished_at=NOW(3)
          WHERE source_code=?
          """,
          safeError(e),
          sourceCode);
      throw e;
    }
  }

  public synchronized Map<String, Object> cancel(String sourceCode) {
    connector(sourceCode);
    database.update(
        """
        UPDATE crawl_records SET status='pending', lease_owner=NULL, lease_until=NULL
        WHERE source_code=? AND status='processing'
        """,
        sourceCode);
    database.update(
        """
        UPDATE crawl_checkpoints
        SET current_status='idle', finished_at=NOW(3),
            lease_owner=NULL, lease_until=NULL
        WHERE source_code=? AND current_status IN ('planning', 'running')
        """,
        sourceCode);
    return source(sourceCode);
  }

  public Map<String, Object> records(String sourceCode, String status, int page, int size) {
    boolean allSources = sourceCode == null || sourceCode.isBlank() || "all".equals(sourceCode);
    if (!allSources) connector(sourceCode);
    int safePage = Math.max(1, page);
    int safeSize = Math.max(10, Math.min(100, size));
    String selectedStatus = status == null || status.isBlank() ? "all" : status;
    StringBuilder where = new StringBuilder(" WHERE 1=1");
    List<Object> args = new ArrayList<>();
    if (!allSources) {
      where.append(" AND source_code=?");
      args.add(sourceCode);
    }
    if ("queued".equals(selectedStatus)) {
      where.append(" AND status IN ('pending', 'processing', 'retry_wait')");
    } else if (!"all".equals(selectedStatus)) {
      if (!List.of("imported", "duplicate", "ignored", "failed").contains(selectedStatus)) {
        throw new IllegalArgumentException("不支持的爬取记录状态: " + selectedStatus);
      }
      where.append(" AND status=?");
      args.add(selectedStatus);
    }
    long total =
        ((Number) database.scalar("SELECT COUNT(*) FROM crawl_records" + where, args.toArray()))
            .longValue();
    args.add(safeSize);
    args.add((safePage - 1L) * safeSize);
    List<Map<String, Object>> items =
        database.list(
            "SELECT * FROM crawl_records" + where + " ORDER BY id DESC LIMIT ? OFFSET ?",
            args.toArray());
    List<Map<String, Object>> enriched =
        items.stream()
            .map(
                item -> {
                  Map<String, Object> row = new LinkedHashMap<>(item);
                  String code = String.valueOf(item.get("source_code"));
                  CrawlConnector source = connectors.get(code);
                  row.put("source_name", source == null ? code : source.sourceName());
                  return row;
                })
            .toList();
    return Map.of(
        "items", enriched,
        "page", safePage,
        "size", safeSize,
        "totalElements", total,
        "totalPages", total == 0 ? 0 : (total + safeSize - 1) / safeSize);
  }

  void finishIfComplete(String sourceCode) {
    Number active =
        (Number)
            database.scalar(
                """
                SELECT COUNT(*) FROM crawl_records
                WHERE source_code=? AND status IN ('pending', 'processing', 'retry_wait')
                """,
                sourceCode);
    if (active != null && active.longValue() > 0) return;
    Number failed =
        (Number)
            database.scalar(
                "SELECT COUNT(*) FROM crawl_records WHERE source_code=? AND status='failed'",
                sourceCode);
    if (failed != null && failed.longValue() > 0) {
      database.update(
          """
          UPDATE crawl_checkpoints SET current_status='partial', failed_count=?,
              finished_at=NOW(3) WHERE source_code=? AND current_status='running'
          """,
          failed.longValue(),
          sourceCode);
      return;
    }
    database.update(
        """
        UPDATE crawl_checkpoints
        SET checkpoint=pending_checkpoint, pending_checkpoint=NULL,
            current_status='idle', finished_at=NOW(3),
            last_successful_at=NOW(3), lease_owner=NULL, lease_until=NULL
        WHERE source_code=? AND current_status='running'
        """,
        sourceCode);
  }

  public void recoverStalePlanning() {
    database.update(
        """
        UPDATE crawl_checkpoints SET current_status='failed',
            error_summary='任务规划超时', finished_at=NOW(3)
        WHERE current_status='planning'
          AND updated_at < DATE_SUB(NOW(3), INTERVAL 10 MINUTE)
        """);
  }

  private void ensureCheckpoint(String sourceCode) {
    database.update("INSERT IGNORE INTO crawl_checkpoints(source_code) VALUES (?)", sourceCode);
  }

  private CrawlConnector connector(String sourceCode) {
    CrawlConnector connector = connectors.get(sourceCode);
    if (connector == null) throw new IllegalArgumentException("不支持的爬取来源: " + sourceCode);
    return connector;
  }

  CrawlConnector connectorForWorker(String sourceCode) {
    return connector(sourceCode);
  }

  private void ensureEnabled(String sourceCode) {
    if (!properties.isEnabled() || !sourceEnabled(sourceCode)) {
      throw new IllegalStateException("爬虫来源未启用: " + sourceCode);
    }
  }

  private boolean sourceEnabled(String sourceCode) {
    return PopCidianConnector.SOURCE_CODE.equals(sourceCode)
        && properties.getPopcidian().isEnabled();
  }

  private JsonNode checkpoint(String sourceCode) {
    Object value =
        database.scalar("SELECT checkpoint FROM crawl_checkpoints WHERE source_code=?", sourceCode);
    if (value == null) return null;
    try {
      return value instanceof JsonNode node ? node : mapper.readTree(String.valueOf(value));
    } catch (Exception e) {
      throw new IllegalStateException("来源检查点无效: " + sourceCode, e);
    }
  }

  private String json(JsonNode node) {
    if (node == null || node.isNull()) return null;
    try {
      return mapper.writeValueAsString(node);
    } catch (Exception e) {
      throw new IllegalStateException("无法保存来源检查点", e);
    }
  }

  private String safeError(Exception exception) {
    String value =
        exception.getMessage() == null
            ? exception.getClass().getSimpleName()
            : exception.getMessage();
    return value.length() > 2000 ? value.substring(0, 2000) : value;
  }
}
