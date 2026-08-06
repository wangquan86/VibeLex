package com.vibelex.recommendation.application;

import com.vibelex.recommendation.api.RecommendationModels;
import com.vibelex.search.ElasticsearchGateway;
import com.vibelex.search.ElasticsearchGateway.Hit;
import com.vibelex.search.EmbeddingProvider;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class RecommendationService {
  private static final Logger log = LoggerFactory.getLogger(RecommendationService.class);
  private final RecommendationProperties properties;
  private final ElasticsearchGateway es;
  private final EmbeddingProvider embedding;
  private final RerankerClient reranker;
  private final RecommendationOriginQuery originQuery;

  public RecommendationService(
      RecommendationProperties properties,
      ElasticsearchGateway es,
      EmbeddingProvider embedding,
      RerankerClient reranker,
      RecommendationOriginQuery originQuery) {
    this.properties = properties;
    this.es = es;
    this.embedding = embedding;
    this.reranker = reranker;
    this.originQuery = originQuery;
  }

  public RecommendationModels.Response recommend(RecommendationModels.Request request) {
    long started = System.nanoTime();
    if (!properties.isEnabled()) throw new RecommendationUnavailableException("V3 推荐功能未启用");
    if (!es.enabled()) throw new RecommendationUnavailableException("Elasticsearch 未启用");

    String context = request.context() == null ? "" : request.context().trim();
    if (context.isEmpty()) throw new IllegalArgumentException("context 去除首尾空白后不能为空");
    int contextLength = context.codePointCount(0, context.length());
    if (contextLength > properties.getMaxContextCharacters())
      throw new RecommendationContextTooLongException();
    String language = request.languageCode() == null ? "zh-CN" : request.languageCode();
    if (!"zh-CN".equals(language)) throw new IllegalArgumentException("V3.2 仅支持 zh-CN");
    int maxResults =
        request.maxResults() == null ? properties.getDefaultMaxResults() : request.maxResults();
    if (maxResults < 1 || maxResults > properties.getMaxResultsLimit())
      throw new IllegalArgumentException(
          "max_results 必须在 1 到 " + properties.getMaxResultsLimit() + " 之间");

    String requestId = UUID.randomUUID().toString();
    long embeddingStarted = System.nanoTime();
    List<Float> vector;
    try {
      vector = embedding.embed(context);
    } catch (RuntimeException exception) {
      log.warn(
          "V3 recommendation embedding failed requestId={} failureType={}",
          requestId,
          exception.getClass().getSimpleName());
      throw new RecommendationUnavailableException("推荐语义服务不可用", exception);
    }
    long embeddingMillis = elapsedMillis(embeddingStarted);

    long semanticStarted = System.nanoTime();
    List<Hit> semantic;
    try {
      semantic =
          es.knnForRecommendation(vector, properties.getSemanticTopK()).stream()
              .filter(hit -> hit.score() >= properties.getMinimumSemanticScore())
              .toList();
    } catch (RuntimeException exception) {
      log.warn(
          "V3 recommendation semantic search failed requestId={} failureType={}",
          requestId,
          exception.getClass().getSimpleName());
      throw new RecommendationUnavailableException("推荐语义检索不可用", exception);
    }
    long semanticMillis = elapsedMillis(semanticStarted);

    long lexicalStarted = System.nanoTime();
    List<Hit> lexical;
    boolean lexicalSucceeded = true;
    try {
      lexical = es.lexicalForRecommendation(context, properties.getLexicalTopK());
    } catch (RuntimeException exception) {
      lexicalSucceeded = false;
      lexical = List.of();
      log.warn(
          "V3 recommendation lexical search failed; semantic results retained requestId={} failureType={}",
          requestId,
          exception.getClass().getSimpleName());
    }
    long lexicalMillis = elapsedMillis(lexicalStarted);

    long fusionStarted = System.nanoTime();
    FusionResult fusion = fuse(semantic, lexical, maxResults);
    long fusionMillis = elapsedMillis(fusionStarted);
    long rerankerStarted = System.nanoTime();
    RerankResult reranked = rerank(context, fusion.items(), requestId);
    long rerankerMillis = elapsedMillis(rerankerStarted);
    long originStarted = System.nanoTime();
    List<RecommendationModels.Item> recommendations =
        enrichOrigins(reranked.items(), requestId);
    long originMillis = elapsedMillis(originStarted);
    RecommendationModels.Response response =
        new RecommendationModels.Response(
            requestId, recommendations, "3.2", es.indexAlias(), Instant.now());
    log.info(
        "V3 recommendation requestId={} contextCodePoints={} semanticHits={} lexicalHits={} fusedCandidates={} admissionFiltered={} deduplicatedMemes={} returned={} embeddingMillis={} semanticMillis={} lexicalMillis={} fusionMillis={} rerankerMillis={} originMillis={} totalMillis={} lexicalSucceeded={} rerankerAttempted={} rerankerSucceeded={} originEnabled={} engineVersion=3.2 indexAlias={}",
        requestId,
        contextLength,
        semantic.size(),
        lexical.size(),
        fusion.fusedCandidates(),
        fusion.admissionFiltered(),
        fusion.deduplicatedMemes(),
        response.recommendations().size(),
        embeddingMillis,
        semanticMillis,
        lexicalMillis,
        fusionMillis,
        rerankerMillis,
        originMillis,
        elapsedMillis(started),
        lexicalSucceeded,
        reranked.attempted(),
        reranked.succeeded(),
        properties.getOrigin().isEnabled(),
        es.indexAlias());
    return response;
  }

  FusionResult fuse(List<Hit> semantic, List<Hit> lexical, int maxResults) {
    Map<Key, Candidate> candidates = new LinkedHashMap<>();
    addPath(candidates, semantic, true);
    addPath(candidates, lexical, false);
    List<Candidate> ranked =
        candidates.values().stream()
            .filter(candidate -> valid(candidate.hit()))
            .sorted(
                Comparator.comparingDouble(this::score)
                    .reversed()
                    .thenComparing(Comparator.comparingDouble(Candidate::semanticRaw).reversed())
                    .thenComparingLong(candidate -> candidate.hit().memeId())
                    .thenComparingLong(candidate -> candidate.hit().senseId()))
            .toList();
    Set<Long> seenMemes = new LinkedHashSet<>();
    int uniqueMemes =
        (int) ranked.stream().map(candidate -> candidate.hit().memeId()).distinct().count();
    List<RecommendationModels.Item> result = new ArrayList<>();
    for (Candidate candidate : ranked) {
      if (!seenMemes.add(candidate.hit().memeId())) continue;
      result.add(toItem(candidate));
      if (result.size() == maxResults) break;
    }
    return new FusionResult(
        List.copyOf(result),
        candidates.size(),
        Math.max(0, candidates.size() - ranked.size()),
        Math.max(0, ranked.size() - uniqueMemes));
  }

  private void addPath(Map<Key, Candidate> candidates, List<Hit> hits, boolean semantic) {
    Set<Key> ranked = new LinkedHashSet<>();
    int rank = 0;
    for (Hit hit : hits) {
      Key key = new Key(hit.memeId(), hit.senseId());
      if (!ranked.add(key)) continue;
      rank++;
      Candidate current = candidates.getOrDefault(key, new Candidate(hit, 0, 0, 0));
      candidates.put(
          key,
          semantic
              ? new Candidate(hit, rank, current.lexicalRank(), hit.score())
              : new Candidate(current.hit(), current.semanticRank(), rank, current.semanticRaw()));
    }
  }

  private boolean valid(Hit hit) {
    return hit.memeId() > 0
        && hit.senseId() != null
        && hit.senseId() > 0
        && "published".equals(hit.entryStatus())
        && "zh-CN".equals(hit.languageCode());
  }

  private double score(Candidate candidate) {
    double score = 0;
    if (candidate.semanticRank() > 0)
      score +=
          properties.getSemanticWeight()
              * (properties.getRrfRankConstant() + 1d)
              / (properties.getRrfRankConstant() + candidate.semanticRank());
    if (candidate.lexicalRank() > 0)
      score +=
          properties.getLexicalWeight()
              * (properties.getRrfRankConstant() + 1d)
              / (properties.getRrfRankConstant() + candidate.lexicalRank());
    return score;
  }

  private RecommendationModels.Item toItem(Candidate candidate) {
    Hit hit = candidate.hit();
    return new RecommendationModels.Item(
        hit.memeId(),
        hit.memeCode(),
        hit.canonicalTerm(),
        hit.variants(),
        hit.senseId(),
        hit.senseNo(),
        hit.definition(),
        hit.examples().stream().limit(3).toList(),
        hit.category(),
        hit.domainTags(),
        null,
        round6(score(candidate)));
  }

  private RerankResult rerank(
      String context, List<RecommendationModels.Item> items, String requestId) {
    if (!reranker.enabled() || items.isEmpty()) return new RerankResult(items, false, false);
    try {
      List<Double> scores = reranker.rerank(context, items.stream().map(this::rerankerText).toList());
      List<RerankedItem> ranked = new ArrayList<>();
      for (int index = 0; index < items.size(); index++) {
        RecommendationModels.Item item = items.get(index);
        ranked.add(new RerankedItem(item, scores.get(index), item.relevanceScore(), index));
      }
      ranked.sort(
          Comparator.comparingDouble(RerankedItem::score)
              .reversed()
              .thenComparing(RerankedItem::rrfScore, Comparator.reverseOrder())
              .thenComparingInt(RerankedItem::originalIndex));
      return new RerankResult(
          ranked.stream().map(value -> withScore(value.item(), value.score())).toList(), true, true);
    } catch (RuntimeException exception) {
      log.warn(
          "V3 recommendation reranker failed; RRF order retained requestId={} candidates={} failureType={}",
          requestId,
          items.size(),
          exception.getClass().getSimpleName());
      return new RerankResult(items, true, false);
    }
  }

  private String rerankerText(RecommendationModels.Item item) {
    // Rerank against the canonical meaning and metadata only. Examples are display material and
    // may mention words that are unrelated to the candidate's actual sense.
    StringBuilder text = new StringBuilder("词条：").append(item.canonicalTerm());
    if (item.variants() != null && !item.variants().isEmpty())
      text.append("\n变体：").append(String.join("、", item.variants()));
    if (item.definition() != null && !item.definition().isBlank())
      text.append("\n释义：").append(item.definition());
    return text.toString();
  }

  private RecommendationModels.Item withScore(RecommendationModels.Item item, double score) {
    return new RecommendationModels.Item(
        item.memeId(),
        item.memeCode(),
        item.canonicalTerm(),
        item.variants(),
        item.senseId(),
        item.senseNo(),
        item.definition(),
        item.examples(),
        item.category(),
        item.domainTags(),
        item.origin(),
        round6(score));
  }

  private List<RecommendationModels.Item> enrichOrigins(
      List<RecommendationModels.Item> items, String requestId) {
    if (!properties.getOrigin().isEnabled() || items.isEmpty()) return items;
    try {
      Map<Long, RecommendationModels.Origin> origins = originQuery.find(items);
      return items.stream().map(item -> withOrigin(item, origins.get(item.memeId()))).toList();
    } catch (RuntimeException exception) {
      log.warn(
          "V3 recommendation origin enrichment failed; recommendations retained requestId={} candidates={} failureType={}",
          requestId,
          items.size(),
          exception.getClass().getSimpleName());
      return items;
    }
  }

  private RecommendationModels.Item withOrigin(
      RecommendationModels.Item item, RecommendationModels.Origin origin) {
    return new RecommendationModels.Item(
        item.memeId(),
        item.memeCode(),
        item.canonicalTerm(),
        item.variants(),
        item.senseId(),
        item.senseNo(),
        item.definition(),
        item.examples(),
        item.category(),
        item.domainTags(),
        origin,
        item.relevanceScore());
  }

  private BigDecimal round6(double value) {
    return BigDecimal.valueOf(Math.max(0, Math.min(1, value))).setScale(6, RoundingMode.HALF_UP);
  }

  private long elapsedMillis(long started) {
    return (System.nanoTime() - started) / 1_000_000;
  }

  private record Key(long memeId, Long senseId) {}

  private record Candidate(Hit hit, int semanticRank, int lexicalRank, double semanticRaw) {}

  private record RerankedItem(
      RecommendationModels.Item item, double score, BigDecimal rrfScore, int originalIndex) {}

  private record RerankResult(
      List<RecommendationModels.Item> items, boolean attempted, boolean succeeded) {}

  record FusionResult(
      List<RecommendationModels.Item> items,
      int fusedCandidates,
      int admissionFiltered,
      int deduplicatedMemes) {}
}
