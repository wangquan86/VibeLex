package com.vibelex.recommendation.application;

import com.vibelex.recommendation.api.RecommendationModels;
import com.vibelex.shared.persistence.MyBatisDatabase;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class RecommendationOriginQuery {
  private final MyBatisDatabase database;
  private final RecommendationProperties properties;

  public RecommendationOriginQuery(
      MyBatisDatabase database, RecommendationProperties properties) {
    this.database = database;
    this.properties = properties;
  }

  public Map<Long, RecommendationModels.Origin> find(
      List<RecommendationModels.Item> recommendations) {
    if (recommendations.isEmpty()) return Map.of();

    Map<Long, Long> selectedSenses = new LinkedHashMap<>();
    for (RecommendationModels.Item item : recommendations)
      selectedSenses.put(item.memeId(), item.senseId());
    List<Long> memeIds = List.copyOf(selectedSenses.keySet());
    String placeholders = String.join(", ", Collections.nCopies(memeIds.size(), "?"));
    Object[] args = memeIds.toArray();

    Map<Long, String> summaries = summaries(placeholders, args);
    Map<Long, List<RecommendationModels.OriginEvidence>> evidence =
        evidence(placeholders, args, selectedSenses);

    Map<Long, RecommendationModels.Origin> result = new LinkedHashMap<>();
    for (Long memeId : memeIds)
      result.put(
          memeId,
          new RecommendationModels.Origin(
              summaries.get(memeId), List.copyOf(evidence.getOrDefault(memeId, List.of()))));
    return Map.copyOf(result);
  }

  private Map<Long, String> summaries(String placeholders, Object[] args) {
    Map<Long, String> result = new LinkedHashMap<>();
    for (Map<String, Object> row :
        database.list(
            "SELECT id AS meme_id, origin_summary FROM meme_entries "
                + "WHERE status='published' AND id IN ("
                + placeholders
                + ")",
            args)) {
      String summary = nullableText(row, "origin_summary");
      if (summary != null && !summary.isBlank()) result.put(number(row, "meme_id"), summary);
    }
    return result;
  }

  private Map<Long, List<RecommendationModels.OriginEvidence>> evidence(
      String placeholders, Object[] args, Map<Long, Long> selectedSenses) {
    Map<Long, List<RecommendationModels.OriginEvidence>> result = new LinkedHashMap<>();
    for (Map<String, Object> row :
        database.list(
            """
            SELECT meme_id, sense_id, source_name, source_url, source_layer,
                   evidence_note, observed_at, confidence
            FROM meme_evidence
            WHERE status='active' AND evidence_role='origin' AND meme_id IN (
            """
                + placeholders
                + ") ORDER BY meme_id, observed_at IS NULL, observed_at, id",
            args)) {
      long memeId = number(row, "meme_id");
      Long selectedSense = selectedSenses.get(memeId);
      Long evidenceSense = nullableNumber(row, "sense_id");
      if (selectedSense == null
          || (evidenceSense != null && !evidenceSense.equals(selectedSense))) continue;

      List<RecommendationModels.OriginEvidence> items =
          result.computeIfAbsent(memeId, ignored -> new ArrayList<>());
      if (items.size() >= properties.getOrigin().getMaxEvidencePerItem()) continue;
      items.add(
          new RecommendationModels.OriginEvidence(
              evidenceSense,
              text(row, "source_name"),
              nullableText(row, "source_url"),
              text(row, "source_layer"),
              nullableText(row, "evidence_note"),
              instant(row.get("observed_at")),
              decimal(row.get("confidence"))));
    }
    return result;
  }

  private long number(Map<String, Object> row, String key) {
    return ((Number) row.get(key)).longValue();
  }

  private Long nullableNumber(Map<String, Object> row, String key) {
    Object value = row.get(key);
    return value instanceof Number number ? number.longValue() : null;
  }

  private String text(Map<String, Object> row, String key) {
    return String.valueOf(row.get(key));
  }

  private String nullableText(Map<String, Object> row, String key) {
    Object value = row.get(key);
    return value == null ? null : String.valueOf(value);
  }

  private BigDecimal decimal(Object value) {
    if (value == null) return null;
    if (value instanceof BigDecimal decimal) return decimal;
    if (value instanceof Number number) return BigDecimal.valueOf(number.doubleValue());
    return new BigDecimal(String.valueOf(value));
  }

  private Instant instant(Object value) {
    if (value instanceof Instant instant) return instant;
    if (value instanceof Timestamp timestamp)
      return timestamp.toLocalDateTime().toInstant(ZoneOffset.UTC);
    if (value instanceof LocalDateTime dateTime) return dateTime.toInstant(ZoneOffset.UTC);
    return value == null
        ? null
        : LocalDateTime.parse(String.valueOf(value).replace(' ', 'T')).toInstant(ZoneOffset.UTC);
  }
}
