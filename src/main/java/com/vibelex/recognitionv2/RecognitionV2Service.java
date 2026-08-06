package com.vibelex.recognitionv2;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.vibelex.candidatediscovery.domain.NormalizationProfile;
import com.vibelex.candidatediscovery.domain.TermNormalizer;
import com.vibelex.recognition.application.RecognitionService;
import com.vibelex.search.ElasticsearchGateway;
import com.vibelex.search.EmbeddingProvider;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Coordinates rule, lexical and semantic recall into one V1 validation pipeline. */
@Service
public class RecognitionV2Service {
  private static final Logger log = LoggerFactory.getLogger(RecognitionV2Service.class);
  private final RecognitionService v1;
  private final RecognitionV2Properties properties;
  private final ElasticsearchGateway es;
  private final EmbeddingProvider embedding;
  private final TermNormalizer normalizer;

  public RecognitionV2Service(
      RecognitionService v1,
      RecognitionV2Properties properties,
      ElasticsearchGateway es,
      EmbeddingProvider embedding,
      TermNormalizer normalizer) {
    this.v1 = v1;
    this.properties = properties;
    this.es = es;
    this.embedding = embedding;
    this.normalizer = normalizer;
  }

  public record Options(
      @JsonProperty("min_confidence") Double minConfidence,
      @JsonProperty("max_results") Integer maxResults,
      @JsonProperty("enable_semantic_recall") Boolean enableSemanticRecall) {}

  public record Request(
      @NotBlank String text, @JsonProperty("language_code") String languageCode, Options options) {}

  public Map<String, Object> recognize(Request request) {
    if (!properties.isEnabled())
      throw new IllegalStateException("V2 recognition engine is disabled");
    if (request.text().codePointCount(0, request.text().length())
        > properties.getSentenceMaxCharacters()) throw new TextTooLongException();
    double min =
        request.options() == null || request.options().minConfidence() == null
            ? properties.getMinConfidence()
            : request.options().minConfidence();
    int max =
        request.options() == null || request.options().maxResults() == null
            ? properties.getMaxResults()
            : request.options().maxResults();

    String requestId = UUID.randomUUID().toString();
    boolean degraded = false;
    int lexicalCount = 0;
    int semanticCount = 0;
    Map<String, Set<String>> candidateSources = new LinkedHashMap<>();

    if (es.enabled()) {
      try {
        List<String> queryUnits = lexicalQueries(request);
        List<ElasticsearchGateway.Hit> hits =
            es.lexicalForRecognition(queryUnits, properties.getLexicalTopK());
        lexicalCount = hits.size();
        for (ElasticsearchGateway.Hit hit : hits) {
          addAnchoredCandidate(request, hit, "lexical", candidateSources);
        }
        log.info(
            "V2 lexical recall queryUnits={} hits={} anchoredCandidates={} units={}",
            queryUnits.size(),
            lexicalCount,
            candidateSources.size(),
            queryUnits);
        log.info(
            "V2 lexical recall topHits={}",
            hits.stream().limit(20).map(hit -> hit.memeId() + ":" + hit.senseId()).toList());
      } catch (RuntimeException ex) {
        if (!properties.isFallbackToV1OnSearchFailure()) throw ex;
        degraded = true;
        log.warn("V2 lexical recall unavailable; preserving rule candidates", ex);
      }

      if (semanticEnabled(request)) {
        try {
          for (String fragment : fragments(request.text())) {
            for (ElasticsearchGateway.Hit hit :
                es.knnForRecognition(embedding.embed(fragment), properties.getSemanticTopK())) {
              if (hit.score() >= properties.getMinimumSemanticScore()) {
                semanticCount++;
                addAnchoredCandidate(request, hit, "semantic", candidateSources);
              }
            }
          }
        } catch (RuntimeException ex) {
          if (!properties.isFallbackToV1OnEmbeddingFailure()) throw ex;
          degraded = true;
          log.warn("V2 semantic recall unavailable; preserving rule and lexical candidates", ex);
        }
      }
    } else {
      degraded = true;
    }

    Map<String, Object> base =
        v1.recognizeWithCandidates(
            new RecognitionService.Request(
                request.text(),
                request.languageCode(),
                null,
                new RecognitionService.Options(min, max)),
            flatten(candidateSources));
    List<Map<String, Object>> matches = copyMatches(base.get("matches"));
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("request_id", requestId);
    out.put("matches", matches);
    out.put("engine_version", "2.0");
    out.put("index_version", es.indexAlias());
    out.put("degraded", degraded);
    out.put("processed_at", Instant.now().toString());
    log.info(
        "V2 recognition requestId={} lexicalCandidates={} semanticCandidates={} anchoredCandidates={} finalMatches={} degraded={}",
        requestId,
        lexicalCount,
        semanticCount,
        candidateSources.size(),
        matches.size(),
        degraded);
    return out;
  }

  private boolean semanticEnabled(Request request) {
    return properties.isSemanticRecallEnabled()
        && (request.options() == null
            || !Boolean.FALSE.equals(request.options().enableSemanticRecall()));
  }

  /**
   * ES is allowed to nominate an entry, not invent a result span. Only a canonical term or variant
   * that can be located through the V1 normalization views becomes an external candidate.
   * Consequently a pure semantic hit is discarded; an anchored semantic hit carries lexical
   * corroboration as well.
   */
  private void addAnchoredCandidate(
      Request request,
      ElasticsearchGateway.Hit hit,
      String source,
      Map<String, Set<String>> candidates) {
    List<String> terms = new ArrayList<>();
    terms.add(hit.canonicalTerm());
    terms.addAll(hit.variants());
    for (RecognitionService.Candidate candidate :
        v1.anchorCandidates(
            request.text(), request.languageCode(), hit.memeId(), hit.senseId(), terms, source)) {
      String key =
          candidate.memeId()
              + ":"
              + (candidate.senseId() == null ? "" : candidate.senseId())
              + ":"
              + candidate.startOffset()
              + ":"
              + candidate.endOffset();
      Set<String> sources = candidates.computeIfAbsent(key, ignored -> new LinkedHashSet<>());
      sources.add(source);
      if ("semantic".equals(source)) sources.add("lexical");
    }
  }

  /**
   * Builds a bounded set of sentence and short-clause lexical query units for one _msearch call.
   */
  private List<String> lexicalQueries(Request request) {
    String language = request.languageCode() == null ? "zh-CN" : request.languageCode();
    Set<String> queries = new LinkedHashSet<>();
    for (String sentence : fragments(request.text())) {
      addQueryForms(queries, sentence, language);
      for (String clause : sentence.split("(?<=[,，、;；])")) addQueryForms(queries, clause, language);
    }
    return queries.stream().limit(12).toList();
  }

  private void addQueryForms(Set<String> queries, String value, String language) {
    String trimmed = value.replaceAll("^[\\s,，、;；]+|[\\s,，、;；。！？!?]+$", "");
    if (trimmed.isBlank()) return;
    queries.add(trimmed);
    for (NormalizationProfile profile :
        List.of(NormalizationProfile.BASE, NormalizationProfile.SPACING)) {
      try {
        queries.add(normalizer.normalize(trimmed, language, profile));
      } catch (IllegalArgumentException ignored) {
      }
    }
  }

  private List<RecognitionService.Candidate> flatten(Map<String, Set<String>> candidateSources) {
    List<RecognitionService.Candidate> candidates = new ArrayList<>();
    candidateSources.forEach(
        (key, sources) -> {
          String[] parts = key.split(":", -1);
          for (String source : sources) {
            candidates.add(
                new RecognitionService.Candidate(
                    Long.parseLong(parts[0]),
                    parts[1].isEmpty() ? null : Long.parseLong(parts[1]),
                    Integer.parseInt(parts[2]),
                    Integer.parseInt(parts[3]),
                    source));
          }
        });
    return candidates;
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> copyMatches(Object raw) {
    List<Map<String, Object>> out = new ArrayList<>();
    if (raw instanceof List<?> list)
      for (Object x : list) out.add(new LinkedHashMap<>((Map<String, Object>) x));
    return out;
  }

  private List<String> fragments(String text) {
    List<String> result = new ArrayList<>();
    for (String part : text.split("(?<=[\\u3002\\uFF01\\uFF1F!?\\n])")) {
      if (!part.isBlank()) {
        result.add(
            part.length() > properties.getSentenceMaxCharacters()
                ? part.substring(0, properties.getSentenceMaxCharacters())
                : part);
      }
    }
    return result.isEmpty() ? List.of(text) : result;
  }

  public static class TextTooLongException extends IllegalArgumentException {
    public TextTooLongException() {
      super("TEXT_TOO_LONG");
    }
  }
}
