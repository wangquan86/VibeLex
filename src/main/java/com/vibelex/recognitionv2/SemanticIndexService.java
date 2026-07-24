package com.vibelex.recognitionv2;

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
public class SemanticIndexService {
  private static final Logger log = LoggerFactory.getLogger(SemanticIndexService.class);
  private final MyBatisDatabase database;
  private final ObjectMapper mapper;
  private final RecognitionV2Properties properties;
  private final ElasticsearchGateway es;
  private final EmbeddingProvider embedding;

  public SemanticIndexService(
      MyBatisDatabase database,
      ObjectMapper mapper,
      RecognitionV2Properties properties,
      ElasticsearchGateway es,
      EmbeddingProvider embedding) {
    this.database = database;
    this.mapper = mapper;
    this.properties = properties;
    this.es = es;
    this.embedding = embedding;
  }

  public void rebuildAll() {
    if (!properties.isEnabled() || !es.enabled() || !properties.getEmbedding().isEnabled()) return;
    String next = properties.getElasticsearch().getIndexName() + "_" + Instant.now().toEpochMilli();
    es.createIndex(next);
    try {
      for (Map<String, Object> row :
          database.list("SELECT id FROM meme_entries WHERE status IN ('published', 'archived')"))
        syncMeme(((Number) row.get("id")).longValue(), next);
      es.putAlias(next, properties.getElasticsearch().getIndexAlias());
    } catch (RuntimeException e) {
      es.deleteIndex(next);
      throw e;
    }
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
            } catch (RuntimeException e) {
              log.warn("V2 ES 全量索引同步失败", e);
            }
          }
        });
  }

  public void syncMemeSafely(long memeId) {
    try {
      syncMeme(memeId);
    } catch (RuntimeException e) {
      log.warn("V2 ES 索引同步失败 memeId={}", memeId, e);
    }
  }

  public void deleteMeme(long memeId) {
    if (!properties.isEnabled() || !es.enabled()) return;
    deleteKnownDocuments(memeId);
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

  public void syncMeme(long memeId) {
    if (!properties.isEnabled() || !es.enabled() || !properties.getEmbedding().isEnabled()) return;
    ensureIndex();
    Map<String, Object> entry =
        database.optionalOne(
            """
        SELECT e.id,e.meme_code,e.canonical_term,e.status,e.domain_tags,p.detect_enabled,p.risk_level
        FROM meme_entries e JOIN meme_safety_policies p ON p.meme_id=e.id WHERE e.id=?
        """,
            memeId);
    if (entry == null || !eligible(entry)) {
      deleteKnownDocuments(memeId);
      return;
    }
    List<Map<String, Object>> senses =
        database.list(
            "SELECT id,sense_no,short_definition,definition,usage_context,semantic_tags FROM meme_senses WHERE meme_id=? AND status='active' ORDER BY sense_no",
            memeId);
    if (senses.isEmpty()) index(entry, null, properties.getElasticsearch().getIndexAlias());
    else
      for (Map<String, Object> sense : senses)
        index(entry, sense, properties.getElasticsearch().getIndexAlias());
  }

  private void syncMeme(long memeId, String targetIndex) {
    Map<String, Object> entry =
        database.optionalOne(
            "SELECT e.id,e.meme_code,e.canonical_term,e.status,e.domain_tags,p.detect_enabled,p.risk_level FROM meme_entries e JOIN meme_safety_policies p ON p.meme_id=e.id WHERE e.id=?",
            memeId);
    if (entry == null || !eligible(entry)) return;
    List<Map<String, Object>> senses =
        database.list(
            "SELECT id,sense_no,short_definition,definition,usage_context,semantic_tags FROM meme_senses WHERE meme_id=? AND status='active' ORDER BY sense_no",
            memeId);
    if (senses.isEmpty()) index(entry, null, targetIndex);
    else for (Map<String, Object> sense : senses) index(entry, sense, targetIndex);
  }

  public Map<String, Object> status() {
    return es.status();
  }

  private void ensureIndex() {
    if (!es.enabled()) return;
    String physical = properties.getElasticsearch().getIndexName();
    if (!es.indexExists(physical)) {
      es.createIndex(physical);
      es.putAlias(physical, properties.getElasticsearch().getIndexAlias());
    }
  }

  private void index(Map<String, Object> entry, Map<String, Object> sense, String targetIndex) {
    long memeId = ((Number) entry.get("id")).longValue();
    Long senseId = sense == null ? null : ((Number) sense.get("id")).longValue();
    List<String> variants = variants(memeId, senseId);
    String definition = sense == null ? "" : text(sense, "definition");
    List<String> tags =
        concat(
            jsonStrings(entry.get("domain_tags")),
            sense == null ? List.of() : jsonStrings(sense.get("semantic_tags")));
    List<String> scenes = sense == null ? List.of() : jsonStrings(sense.get("usage_context"));
    String embeddingText =
        "词条："
            + text(entry, "canonical_term")
            + "。变体："
            + String.join("；", variants)
            + "。释义："
            + definition
            + "。场景："
            + String.join("；", scenes)
            + "。标签："
            + String.join("；", tags)
            + "。";
    Map<String, Object> document = new LinkedHashMap<>();
    document.put("meme_id", memeId);
    document.put("sense_id", senseId);
    document.put("meme_code", text(entry, "meme_code"));
    document.put("canonical_term", text(entry, "canonical_term"));
    document.put("variants", variants);
    document.put("definition", definition);
    document.put("tags", tags);
    document.put("scenes", scenes);
    document.put("risk_level", text(entry, "risk_level"));
    document.put("detect_enabled", true);
    document.put("indexed_at", Instant.now().toString());
    document.put("embedding", embedding.embed(embeddingText));
    es.upsert(targetIndex, id(memeId, senseId), document);
  }

  private void deleteKnownDocuments(long memeId) {
    for (Map<String, Object> s :
        database.list("SELECT id FROM meme_senses WHERE meme_id=?", memeId))
      es.delete(
          properties.getElasticsearch().getIndexAlias(),
          id(memeId, ((Number) s.get("id")).longValue()));
    es.delete(properties.getElasticsearch().getIndexAlias(), id(memeId, null));
  }

  private List<String> variants(long memeId, Long senseId) {
    List<Object> args = new ArrayList<>();
    String where = "meme_id=? AND status='active'";
    args.add(memeId);
    if (senseId != null) {
      where += " AND (sense_id IS NULL OR sense_id=?)";
      args.add(senseId);
    }
    List<String> r = new ArrayList<>();
    for (Map<String, Object> row :
        database.list("SELECT variant FROM meme_variants WHERE " + where, args.toArray()))
      r.add(text(row, "variant"));
    return r;
  }

  private boolean eligible(Map<String, Object> entry) {
    return List.of("published", "archived").contains(text(entry, "status"))
        && bool(entry.get("detect_enabled"));
  }

  private String id(long meme, Long sense) {
    return "meme-" + meme + "-sense-" + (sense == null ? "entry" : sense);
  }

  private String text(Map<String, Object> row, String key) {
    Object v = row.get(key);
    return v == null ? "" : String.valueOf(v);
  }

  private boolean bool(Object v) {
    return v instanceof Boolean b ? b : v instanceof Number n && n.intValue() != 0;
  }

  private List<String> jsonStrings(Object value) {
    if (value == null) return List.of();
    try {
      JsonNode root = mapper.readTree(String.valueOf(value));
      List<String> r = new ArrayList<>();
      if (root.isArray()) for (JsonNode x : root) r.add(x.asText());
      return r;
    } catch (Exception e) {
      return List.of();
    }
  }

  private List<String> concat(List<String> a, List<String> b) {
    List<String> r = new ArrayList<>(a);
    r.addAll(b);
    return r;
  }
}
