package com.vibelex.search;

import com.vibelex.shared.persistence.MyBatisDatabase;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class SearchIndexService {
  private static final Logger log = LoggerFactory.getLogger(SearchIndexService.class);
  private final MyBatisDatabase database;
  private final ObjectMapper mapper;
  private final SearchProperties properties;
  private final ElasticsearchGateway es;
  private final EmbeddingProvider embedding;

  public SearchIndexService(
      MyBatisDatabase database,
      ObjectMapper mapper,
      SearchProperties properties,
      ElasticsearchGateway es,
      EmbeddingProvider embedding) {
    this.database = database;
    this.mapper = mapper;
    this.properties = properties;
    this.es = es;
    this.embedding = embedding;
  }

  public RebuildReport rebuildAll() {
    requireAvailable();
    String next = es.indexName() + "_" + Instant.now().toEpochMilli();
    es.createIndex(next);
    int documents = 0;
    List<Long> entriesWithoutActiveSense = new ArrayList<>();
    try {
      for (Map<String, Object> row :
          database.list("SELECT id FROM meme_entries WHERE status = 'published'")) {
        long memeId = ((Number) row.get("id")).longValue();
        int written = syncMeme(memeId, next, false);
        documents += written;
        if (written == 0) entriesWithoutActiveSense.add(memeId);
      }
    } catch (RuntimeException exception) {
      try {
        es.deleteIndex(next);
      } catch (RuntimeException cleanupFailure) {
        exception.addSuppressed(cleanupFailure);
      }
      throw exception;
    }

    // Once cutover starts, never delete the new index on an ambiguous transport failure: the
    // atomic alias request may already have been applied by Elasticsearch.
    List<String> previousIndices = es.switchAlias(next);
    List<String> deletedIndices = new ArrayList<>();
    List<String> cleanupFailures = new ArrayList<>();
    for (String previous : previousIndices) {
      try {
        es.deleteIndex(previous);
        deletedIndices.add(previous);
      } catch (RuntimeException exception) {
        cleanupFailures.add(previous);
        log.error("共享义项索引切换成功，但旧索引删除失败 index={}", previous, exception);
      }
    }
    RebuildReport report =
        new RebuildReport(
            next,
            List.copyOf(previousIndices),
            List.copyOf(deletedIndices),
            List.copyOf(cleanupFailures),
            documents,
            List.copyOf(entriesWithoutActiveSense));
    log.info(
        "共享义项索引全量重建完成 index={} documents={} entriesWithoutActiveSense={} deletedOldIndices={} cleanupFailures={}",
        next,
        documents,
        entriesWithoutActiveSense.size(),
        deletedIndices,
        cleanupFailures);
    return report;
  }

  public void rebuildAllAfterCommit() {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      rebuildAll();
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            try {
              rebuildAll();
            } catch (RuntimeException exception) {
              log.warn("共享义项索引全量同步失败", exception);
            }
          }
        });
  }

  public void syncMeme(long memeId) {
    requireAvailable();
    requireCompatibleIndex();
    syncMeme(memeId, es.indexAlias(), true);
  }

  /** Indexes one meme into a caller-owned physical index during an async rebuild. */
  public void syncMemeToIndex(long memeId, String targetIndex) {
    requireAvailable();
    syncMeme(memeId, targetIndex, false);
  }

  public void syncMemeSafely(long memeId) {
    try {
      syncMeme(memeId);
    } catch (RuntimeException exception) {
      log.warn("共享义项索引同步失败 memeId={}", memeId, exception);
    }
  }

  public void deleteMeme(long memeId) {
    requireCompatibleIndex();
    es.deleteByMeme(es.indexAlias(), memeId);
  }

  public void syncMemeAfterCommit(long memeId) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      syncMemeSafely(memeId);
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            syncMemeSafely(memeId);
          }
        });
  }

  public Map<String, Object> status() {
    return es.status();
  }

  private int syncMeme(long memeId, String targetIndex, boolean deleteExisting) {
    if (deleteExisting) es.deleteByMeme(targetIndex, memeId);
    Map<String, Object> entry =
        database.optionalOne(
            """
            SELECT id, meme_code, canonical_term, language_code, status, category, domain_tags
            FROM meme_entries
            WHERE id=? AND status = 'published'
            """,
            memeId);
    if (entry == null) return 0;
    List<Map<String, Object>> senses =
        database.list(
            """
            SELECT id, sense_no, definition
            FROM meme_senses
            WHERE meme_id=? AND status='active'
            ORDER BY sense_no, id
            """,
            memeId);
    for (Map<String, Object> sense : senses) index(entry, sense, targetIndex);
    return senses.size();
  }

  private void index(Map<String, Object> entry, Map<String, Object> sense, String targetIndex) {
    long memeId = ((Number) entry.get("id")).longValue();
    long senseId = ((Number) sense.get("id")).longValue();
    List<String> variants = variants(memeId, senseId);
    List<String> examples = examples(memeId, senseId);
    List<String> domainTags = jsonStrings(entry.get("domain_tags"));
    String definition = text(sense, "definition");
    // Keep the retrieval embedding focused on the sense itself. Examples are retained for
    // display, but can contain incidental words from unrelated situations and introduce
    // lexical leakage into semantic recommendation.
    String embeddingText =
        "词条："
            + text(entry, "canonical_term")
            + "。变体："
            + String.join("；", variants)
            + "。释义："
            + definition
            + "。分类："
            + text(entry, "category")
            + "。领域标签："
            + String.join("；", domainTags)
            + "。";
    Map<String, Object> document = new LinkedHashMap<>();
    document.put("meme_id", memeId);
    document.put("sense_id", senseId);
    document.put("sense_no", ((Number) sense.get("sense_no")).intValue());
    document.put("meme_code", text(entry, "meme_code"));
    document.put("canonical_term", text(entry, "canonical_term"));
    document.put("variants", variants);
    document.put("language_code", text(entry, "language_code"));
    document.put("entry_status", text(entry, "status"));
    document.put("category", text(entry, "category"));
    document.put("domain_tags", domainTags);
    document.put("definition", definition);
    document.put("examples", examples);
    document.put("embedding", embedding.embed(embeddingText));
    es.upsert(targetIndex, documentId(memeId, senseId), document);
  }

  private List<String> variants(long memeId, long senseId) {
    List<String> result = new ArrayList<>();
    for (Map<String, Object> row :
        database.list(
            """
            SELECT variant
            FROM meme_variants
            WHERE meme_id=? AND status='active' AND (sense_id IS NULL OR sense_id=?)
            ORDER BY id
            """,
            memeId,
            senseId)) result.add(text(row, "variant"));
    return List.copyOf(result);
  }

  private List<String> examples(long memeId, long senseId) {
    List<String> result = new ArrayList<>();
    for (Map<String, Object> row :
        database.list(
            """
            SELECT example_text
            FROM meme_examples
            WHERE meme_id=? AND status='approved' AND example_role='positive'
              AND (sense_id IS NULL OR sense_id=?)
            ORDER BY id
            LIMIT 3
            """,
            memeId,
            senseId)) result.add(text(row, "example_text"));
    return List.copyOf(result);
  }

  private String documentId(long memeId, long senseId) {
    return "meme-" + memeId + "-sense-" + senseId;
  }

  private String text(Map<String, Object> row, String key) {
    Object value = row.get(key);
    return value == null ? "" : String.valueOf(value);
  }

  private List<String> jsonStrings(Object value) {
    if (value == null) return List.of();
    try {
      JsonNode root = mapper.readTree(String.valueOf(value));
      List<String> result = new ArrayList<>();
      if (root.isArray()) for (JsonNode item : root) result.add(item.asText());
      return List.copyOf(result);
    } catch (Exception ignored) {
      return List.of();
    }
  }

  void requireAvailable() {
    if (!es.enabled()) throw new SearchIndexNotReadyException("Elasticsearch 未启用，索引同步未执行");
    if (!properties.getEmbedding().isEnabled())
      throw new SearchIndexNotReadyException("embedding 服务未启用，索引同步未执行");
  }

  private void requireCompatibleIndex() {
    if (!es.enabled()) throw new SearchIndexNotReadyException("Elasticsearch 未启用，索引同步未执行");
    if (!es.aliasExists()) throw new SearchIndexNotReadyException("共享义项索引别名不存在，请先执行全量重建");
    if (!es.aliasCompatible())
      throw new SearchIndexNotReadyException("当前索引 mapping 不是 V3.2，请先执行全量重建和别名切换");
  }

  public record RebuildReport(
      String index,
      List<String> previousIndices,
      List<String> deletedIndices,
      List<String> cleanupFailures,
      int documents,
      List<Long> entriesWithoutActiveSense) {}
}
