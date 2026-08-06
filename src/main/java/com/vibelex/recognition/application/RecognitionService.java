package com.vibelex.recognition.application;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.vibelex.candidatediscovery.domain.NormalizationProfile;
import com.vibelex.candidatediscovery.domain.TermNormalizer;
import com.vibelex.recognition.application.RecognitionIndex.*;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 规则识别流水线 V1.1。
 *
 * <p>依次执行归一化视图召回、上下文计分、义项消歧、重叠消解、策略过滤和阈值裁剪。 输出 offset 始终是原始文本的 Unicode 码点半开区间。
 */
@Service
public class RecognitionService {
  private final RecognitionIndex index;
  private final TermNormalizer normalizer;
  private final ObjectMapper mapper;

  public RecognitionService(
      RecognitionIndex index, TermNormalizer normalizer, ObjectMapper mapper) {
    this.index = index;
    this.normalizer = normalizer;
    this.mapper = mapper;
  }

  public record Options(
      @JsonProperty("min_confidence") Double minConfidence,
      @JsonProperty("max_results") Integer maxResults) {}

  public record Request(
      @NotBlank String text,
      @JsonProperty("language_code") String languageCode,
      String scene,
      Options options) {}

  /**
   * An anchored candidate supplied by another recall path. Offsets use the same Unicode code point
   * convention as the public recognition API. Candidates still go through the V1 scoring,
   * disambiguation, overlap and policy pipeline.
   */
  public record Candidate(
      long memeId, Long senseId, int startOffset, int endOffset, String source) {}

  /**
   * Locates ES-nominated terms in original text with the same normalization views used by V1. This
   * keeps V2 offsets lossless when the index term and input differ only in spacing, case,
   * full-width characters or pinyin formatting.
   */
  public List<Candidate> anchorCandidates(
      String text,
      String languageCode,
      long memeId,
      Long senseId,
      List<String> terms,
      String source) {
    String language = languageCode == null ? "zh-CN" : languageCode;
    Map<NormalizationProfile, NormalizedView> views = new EnumMap<>(NormalizationProfile.class);
    for (NormalizationProfile profile : NormalizationProfile.values())
      views.put(profile, NormalizedView.of(text, language, profile, normalizer));
    Map<String, Candidate> result = new LinkedHashMap<>();
    for (String term : terms) {
      if (term == null || term.isBlank()) continue;
      for (NormalizationProfile profile : NormalizationProfile.values()) {
        String needle;
        try {
          needle = normalizer.normalize(term, language, profile);
        } catch (IllegalArgumentException ex) {
          continue;
        }
        NormalizedView view = views.get(profile);
        for (int from = 0;
            (from = view.text.indexOf(needle, from)) >= 0;
            from += Math.max(1, needle.length())) {
          NormalizedView.Span span = view.span(from, from + needle.length());
          Candidate candidate = new Candidate(memeId, senseId, span.start(), span.end(), source);
          result.putIfAbsent(span.start() + ":" + span.end(), candidate);
        }
      }
    }
    return List.copyOf(result.values());
  }

  public Map<String, Object> recognize(Request request) {
    return recognizeWithCandidates(request, List.of());
  }

  public Map<String, Object> recognizeWithCandidates(Request request, List<Candidate> candidates) {
    String language = request.languageCode() == null ? "zh-CN" : request.languageCode();
    double min =
        request.options() == null || request.options().minConfidence() == null
            ? .6
            : request.options().minConfidence();
    int max =
        request.options() == null || request.options().maxResults() == null
            ? 20
            : request.options().maxResults();
    if (min < 0 || min > 1 || max < 1 || max > 200)
      throw new IllegalArgumentException("min_confidence 必须为 0..1，max_results 必须为 1..200");
    Data data = index.get();
    Map<NormalizationProfile, NormalizedView> views = new EnumMap<>(NormalizationProfile.class);
    for (NormalizationProfile p : NormalizationProfile.values())
      views.put(p, NormalizedView.of(request.text(), language, p, normalizer));
    List<Raw> raw = new ArrayList<>();
    for (Anchor a : data.anchors()) {
      Entry e = data.entries().get(a.memeId());
      if (e == null || !e.language().equals(language)) continue;
      if (a.profile() == null)
        findRaw(request.text(), a.value())
            .forEach(
                x ->
                    raw.add(
                        new Raw(
                            a.memeId(),
                            a.senseId(),
                            cpCount(request.text(), x[0]),
                            cpCount(request.text(), x[1]),
                            Set.of(a.source()))));
      else {
        NormalizedView v = views.get(a.profile());
        findRaw(v.text, a.value())
            .forEach(
                x -> {
                  NormalizedView.Span s = v.span(x[0], x[1]);
                  raw.add(new Raw(a.memeId(), a.senseId(), s.start(), s.end(), Set.of(a.source())));
                });
      }
    }
    for (var en : data.rules().entrySet())
      for (Rule r : en.getValue())
        if ("regex_match".equals(r.type())) {
          try {
            var m = Pattern.compile(r.value()).matcher(request.text());
            while (m.find())
              raw.add(
                  new Raw(
                      en.getKey(),
                      r.senseId(),
                      cpCount(request.text(), m.start()),
                      cpCount(request.text(), m.end()),
                      Set.of("rule:regex_match")));
          } catch (RuntimeException ignored) {
          }
        }
    for (Candidate candidate : candidates) {
      if (candidate.startOffset() < 0
          || candidate.endOffset() <= candidate.startOffset()
          || candidate.endOffset() > request.text().codePointCount(0, request.text().length()))
        continue;
      raw.add(
          new Raw(
              candidate.memeId(),
              candidate.senseId(),
              candidate.startOffset(),
              candidate.endOffset(),
              Set.of(candidate.source())));
    }
    Map<String, Raw> dedup = new LinkedHashMap<>();
    for (Raw r : raw) {
      String key = r.memeId + ":" + r.senseId + ":" + r.start + ":" + r.end;
      dedup.merge(key, r, (left, right) -> left.withSources(right.sources));
    }
    List<Scored> scored = new ArrayList<>();
    for (Raw r : dedup.values()) scored.addAll(score(r, request.text(), data));
    List<Scored> disambiguated = disambiguate(mergeSameSenseEvidence(scored));
    List<Scored> resolved = resolveOverlaps(disambiguated);
    List<Map<String, Object>> matches =
        resolved.stream()
            .filter(x -> x.confidence >= min)
            .sorted(
                Comparator.comparingDouble(Scored::confidence)
                    .reversed()
                    .thenComparingInt(Scored::start))
            .limit(max)
            .map(x -> output(x, request.text(), data, !candidates.isEmpty()))
            .toList();
    return Map.of(
        "matches", matches, "engine_version", "1.1", "processed_at", Instant.now().toString());
  }

  private List<Scored> score(Raw raw, String text, Data data) {
    List<Sense> senses =
        raw.senseId == null
            ? data.senses().getOrDefault(raw.memeId, List.of())
            : data.senses().getOrDefault(raw.memeId, List.of()).stream()
                .filter(s -> s.id() == raw.senseId)
                .toList();
    if (senses.isEmpty()) senses = Collections.singletonList(null);
    List<Scored> out = new ArrayList<>();
    for (Sense sense : senses) {
      double score = 1.2;
      List<String> reasons = new ArrayList<>();
      for (String source : raw.sources) {
        reasons.add(
            source.contains("normalized") || source.equals("variant")
                ? "normalized_match"
                : source.replace("rule:", ""));
      }
      int senseHits = 0, minPriority = 100;
      boolean veto = false;
      for (Rule rule : data.rules().getOrDefault(raw.memeId, List.of())) {
        if (rule.senseId() != null && (sense == null || rule.senseId() != sense.id())) continue;
        minPriority = Math.min(minPriority, rule.priority());
        if (Set.of("positive_context", "negative_context", "entity_exclusion").contains(rule.type())
            && contextHit(text, raw, rule)) {
          if ("entity_exclusion".equals(rule.type()) && Math.abs(rule.weight()) >= 1) {
            veto = true;
            break;
          }
          score += rule.weight();
          reasons.add(rule.type());
          if (rule.senseId() != null) senseHits++;
        }
      }
      out.add(
          new Scored(
              raw.memeId,
              sense == null ? null : sense.id(),
              sense == null ? null : sense.no(),
              raw.start,
              raw.end,
              veto ? 0 : Math.max(0, Math.min(1, score / 2)),
              reasons,
              senseHits,
              minPriority,
              false,
              recallSources(raw.sources)));
    }
    return out;
  }

  private boolean contextHit(String text, Raw raw, Rule rule) {
    int window = 20;
    try {
      JsonNode c = rule.config() == null ? null : mapper.readTree(rule.config());
      if (c != null && c.has("window")) window = c.get("window").asInt(20);
    } catch (Exception ignored) {
    }
    int[] u =
        cpWindow(
            text,
            Math.max(0, raw.start - window),
            Math.min(text.codePointCount(0, text.length()), raw.end + window));
    String context = text.substring(u[0], u[1]);
    String normalized = normalizer.normalize(context, "zh-CN");
    for (String key : rule.value().split("[,，|]")) {
      if (normalized.contains(normalizer.normalize(key.trim(), "zh-CN"))) return true;
    }
    return false;
  }

  private List<Scored> disambiguate(List<Scored> input) {
    Map<String, List<Scored>> groups = new LinkedHashMap<>();
    input.forEach(
        x ->
            groups
                .computeIfAbsent(x.memeId + ":" + x.start + ":" + x.end, k -> new ArrayList<>())
                .add(x));
    List<Scored> out = new ArrayList<>();
    for (List<Scored> g : groups.values()) {
      g.sort(
          Comparator.comparingDouble(Scored::confidence)
              .reversed()
              .thenComparing(Comparator.comparingInt(Scored::senseHits).reversed()));
      Scored best = g.get(0);
      if (g.size() > 1
          && best.confidence == g.get(1).confidence
          && best.senseHits == g.get(1).senseHits) best = ambiguous(best, g);
      out.add(best);
    }
    return out;
  }

  /**
   * A rule candidate without a sense expands to every sense during scoring. An ES candidate with a
   * concrete sense can therefore produce a second Scored record for that *same* sense. Merge those
   * records before disambiguation so corroborating evidence is not mistaken for sense ambiguity.
   */
  private List<Scored> mergeSameSenseEvidence(List<Scored> input) {
    Map<String, Scored> merged = new LinkedHashMap<>();
    for (Scored candidate : input) {
      String key =
          candidate.memeId + ":" + candidate.senseId + ":" + candidate.start + ":" + candidate.end;
      merged.merge(key, candidate, this::mergeEvidence);
    }
    return new ArrayList<>(merged.values());
  }

  private Scored mergeEvidence(Scored left, Scored right) {
    Set<String> reasons = new LinkedHashSet<>(left.reasons);
    reasons.addAll(right.reasons);
    Set<String> sources = new LinkedHashSet<>(left.recallSources);
    sources.addAll(right.recallSources);
    return new Scored(
        left.memeId,
        left.senseId,
        left.senseNo,
        left.start,
        left.end,
        Math.max(left.confidence, right.confidence),
        List.copyOf(reasons),
        Math.max(left.senseHits, right.senseHits),
        Math.min(left.priority, right.priority),
        left.ambiguous || right.ambiguous,
        Set.copyOf(sources));
  }

  private Scored ambiguous(Scored best, List<Scored> candidates) {
    Set<String> reasons = new LinkedHashSet<>();
    Set<String> sources = new LinkedHashSet<>();
    for (Scored candidate : candidates) {
      reasons.addAll(candidate.reasons);
      sources.addAll(candidate.recallSources);
    }
    return new Scored(
        best.memeId,
        null,
        null,
        best.start,
        best.end,
        best.confidence,
        List.copyOf(reasons),
        best.senseHits,
        best.priority,
        true,
        Set.copyOf(sources));
  }

  private List<Scored> resolveOverlaps(List<Scored> input) {
    input.sort(
        Comparator.comparingDouble(Scored::confidence)
            .reversed()
            .thenComparing(Comparator.comparingInt((Scored x) -> x.end - x.start).reversed())
            .thenComparingInt(Scored::priority)
            .thenComparingLong(Scored::memeId));
    List<Scored> out = new ArrayList<>();
    outer:
    for (Scored c : input) {
      for (Scored k : out)
        if (c.start < k.end && k.start < c.end) {
          boolean nested =
              (c.start >= k.start && c.end <= k.end) || (k.start >= c.start && k.end <= c.end);
          if (!(nested && Math.abs(c.confidence - k.confidence) >= .2)) continue outer;
        }
      out.add(c);
    }
    return out;
  }

  private Map<String, Object> output(
      Scored x, String text, Data data, boolean includeRecallSources) {
    Entry e = data.entries().get(x.memeId);
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("display_enabled", e.display());
    p.put("risk_level", e.risk());
    p.put("moderation_policy", e.moderation());
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("meme_id", e.id());
    m.put("meme_code", e.code());
    m.put("canonical_term", e.term());
    m.put("sense_id", x.senseId);
    m.put("sense_no", x.senseNo);
    m.put("definition", definition(x, data));
    m.put("examples", examples(x, data));
    m.put("ambiguous", x.ambiguous);
    int[] range = cpWindow(text, x.start, x.end);
    m.put("matched_text", text.substring(range[0], range[1]));
    m.put("start_offset", x.start);
    m.put("end_offset", x.end);
    m.put("confidence", Math.round(x.confidence * 10000d) / 10000d);
    m.put("match_reason", x.reasons);
    if (includeRecallSources) m.put("recall_sources", x.recallSources);
    m.put("policy", p);
    return m;
  }

  private String definition(Scored scored, Data data) {
    if (scored.senseId == null) return null;
    return data.senses().getOrDefault(scored.memeId, List.of()).stream()
        .filter(sense -> sense.id() == scored.senseId)
        .map(Sense::definition)
        .findFirst()
        .orElse(null);
  }

  private List<String> examples(Scored scored, Data data) {
    if (scored.senseId == null) return List.of();
    return data.examples().getOrDefault(scored.memeId, List.of()).stream()
        .filter(
            example ->
                example.senseId() == null || Objects.equals(example.senseId(), scored.senseId))
        .map(Example::text)
        .distinct()
        .limit(3)
        .toList();
  }

  private List<int[]> findRaw(String text, String pattern) {
    List<int[]> r = new ArrayList<>();
    if (pattern == null || pattern.isEmpty()) return r;
    for (int from = 0; (from = text.indexOf(pattern, from)) >= 0; from += pattern.length())
      r.add(new int[] {from, from + pattern.length()});
    return r;
  }

  private int cpCount(String s, int utf16) {
    return s.codePointCount(0, utf16);
  }

  private int[] cpWindow(String s, int start, int end) {
    return new int[] {s.offsetByCodePoints(0, start), s.offsetByCodePoints(0, end)};
  }

  private static Set<String> recallSources(Set<String> sources) {
    Set<String> result = new LinkedHashSet<>();
    for (String source : sources) {
      if ("lexical".equals(source) || "semantic".equals(source)) result.add(source);
      else result.add("rule");
    }
    return result;
  }

  private record Raw(long memeId, Long senseId, int start, int end, Set<String> sources) {
    Raw withSources(Set<String> other) {
      Set<String> merged = new LinkedHashSet<>(sources);
      merged.addAll(other);
      return new Raw(memeId, senseId, start, end, Set.copyOf(merged));
    }
  }

  private record Scored(
      long memeId,
      Long senseId,
      Integer senseNo,
      int start,
      int end,
      double confidence,
      List<String> reasons,
      int senseHits,
      int priority,
      boolean ambiguous,
      Set<String> recallSources) {}
}
