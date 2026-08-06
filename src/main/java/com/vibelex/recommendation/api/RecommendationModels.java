package com.vibelex.recommendation.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class RecommendationModels {
  private RecommendationModels() {}

  public record Request(
      @NotBlank String context,
      @JsonProperty("language_code") String languageCode,
      @JsonProperty("max_results") Integer maxResults) {}

  public record Item(
      @JsonProperty("meme_id") long memeId,
      @JsonProperty("meme_code") String memeCode,
      @JsonProperty("canonical_term") String canonicalTerm,
      List<String> variants,
      @JsonProperty("sense_id") long senseId,
      @JsonProperty("sense_no") int senseNo,
      String definition,
      List<String> examples,
      String category,
      @JsonProperty("domain_tags") List<String> domainTags,
      @JsonInclude(JsonInclude.Include.NON_NULL) Origin origin,
      @JsonProperty("relevance_score") BigDecimal relevanceScore) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Origin(String summary, List<OriginEvidence> evidence) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record OriginEvidence(
      @JsonProperty("sense_id") Long senseId,
      @JsonProperty("source_name") String sourceName,
      @JsonProperty("source_url") String sourceUrl,
      @JsonProperty("source_layer") String sourceLayer,
      String note,
      @JsonProperty("observed_at") Instant observedAt,
      BigDecimal confidence) {}

  public record Response(
      @JsonProperty("request_id") String requestId,
      List<Item> recommendations,
      @JsonProperty("engine_version") String engineVersion,
      @JsonProperty("index_version") String indexVersion,
      @JsonProperty("processed_at") Instant processedAt) {}
}
