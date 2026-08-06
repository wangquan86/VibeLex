package com.vibelex.recognition.application;

import com.vibelex.candidatediscovery.domain.NormalizationProfile;
import com.vibelex.candidatediscovery.domain.TermNormalizer;
import com.vibelex.shared.persistence.MyBatisDatabase;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * 已发布词条的只读识别索引。
 *
 * <p>索引使用不可变快照和原子引用切换。刷新期间，正在进行的识别请求继续使用 上一份完整快照，因此不会看到只加载了一半的数据。
 */
@Component
public class RecognitionIndex {

  private static final Set<String> LEXICAL_RULE_TYPES =
      Set.of("exact_match", "normalized_match", "pinyin_match");

  private final MyBatisDatabase database;
  private final TermNormalizer normalizer;
  private final AtomicReference<Data> data =
      new AtomicReference<>(new Data(List.of(), Map.of(), Map.of(), Map.of(), Map.of()));

  public RecognitionIndex(MyBatisDatabase database, TermNormalizer normalizer) {
    this.database = database;
    this.normalizer = normalizer;
  }

  /** 启动时加载一次；发布与回滚成功后由应用服务主动刷新。 */
  @PostConstruct
  public void refresh() {
    Map<Long, Entry> entries = loadEntries();
    Map<Long, List<Sense>> senses = loadSenses();
    Map<Long, List<Example>> examples = loadExamples();
    List<Anchor> anchors = loadVariantAnchors(entries);
    Map<Long, List<Rule>> rules = loadRules(entries, anchors);

    data.set(
        new Data(
            List.copyOf(anchors),
            Map.copyOf(entries),
            immutableLists(senses),
            immutableLists(examples),
            immutableLists(rules)));
  }

  public Data get() {
    return data.get();
  }

  private Map<Long, Entry> loadEntries() {
    Map<Long, Entry> entries = new HashMap<>();
    database
        .list(
            """
                SELECT e.id, e.meme_code, e.canonical_term, e.language_code,
                       e.status, p.risk_level, p.display_enabled,
                       p.moderation_policy
                FROM meme_entries e
                JOIN meme_safety_policies p ON p.meme_id = e.id
                WHERE e.status = 'published'
                """)
        .forEach(
            row -> {
              long id = longValue(row, "id");
              entries.put(
                  id,
                  new Entry(
                      id,
                      stringValue(row, "meme_code"),
                      stringValue(row, "canonical_term"),
                      stringValue(row, "language_code"),
                      stringValue(row, "status"),
                      stringValue(row, "risk_level"),
                      booleanValue(row, "display_enabled"),
                      stringValue(row, "moderation_policy")));
            });
    return entries;
  }

  private Map<Long, List<Sense>> loadSenses() {
    Map<Long, List<Sense>> senses = new HashMap<>();
    database
        .list(
            """
                SELECT id, meme_id, sense_no, definition, safety_policy_override
                FROM meme_senses
                WHERE status = 'active'
                """)
        .forEach(
            row ->
                senses
                    .computeIfAbsent(longValue(row, "meme_id"), ignored -> new ArrayList<>())
                    .add(
                        new Sense(
                            longValue(row, "id"),
                            intValue(row, "sense_no"),
                            stringValue(row, "definition"),
                            nullableString(row, "safety_policy_override"))));
    return senses;
  }

  private Map<Long, List<Example>> loadExamples() {
    Map<Long, List<Example>> examples = new HashMap<>();
    database
        .list(
            """
                SELECT meme_id, sense_id, example_text
                FROM meme_examples
                WHERE status = 'approved'
                  AND example_role = 'positive'
                ORDER BY id
                """)
        .forEach(
            row ->
                examples
                    .computeIfAbsent(longValue(row, "meme_id"), ignored -> new ArrayList<>())
                    .add(
                        new Example(
                            nullableLong(row, "sense_id"), stringValue(row, "example_text"))));
    return examples;
  }

  private List<Anchor> loadVariantAnchors(Map<Long, Entry> entries) {
    List<Anchor> anchors = new ArrayList<>();
    database
        .list(
            """
                SELECT meme_id, sense_id, normalized_variant, variant_type
                FROM meme_variants
                WHERE status = 'active'
                """)
        .forEach(
            row -> {
              long memeId = longValue(row, "meme_id");
              if (!entries.containsKey(memeId)) {
                return;
              }
              anchors.add(
                  new Anchor(
                      memeId,
                      nullableLong(row, "sense_id"),
                      stringValue(row, "normalized_variant"),
                      profile(stringValue(row, "variant_type")),
                      "variant"));
              Entry entry = entries.get(memeId);
              if (profile(stringValue(row, "variant_type")) == NormalizationProfile.BASE
                  && spacingEligible(stringValue(row, "normalized_variant"))) {
                anchors.add(
                    new Anchor(
                        memeId,
                        nullableLong(row, "sense_id"),
                        normalizer.normalize(
                            stringValue(row, "normalized_variant"),
                            entry.language(),
                            NormalizationProfile.SPACING),
                        NormalizationProfile.SPACING,
                        "variant:spacing"));
              }
            });
    return anchors;
  }

  private Map<Long, List<Rule>> loadRules(Map<Long, Entry> entries, List<Anchor> anchors) {
    Map<Long, List<Rule>> rules = new HashMap<>();
    database
        .list(
            """
                SELECT meme_id, sense_id, rule_type, rule_value,
                       rule_config, weight, priority
                FROM meme_match_rules
                WHERE enabled = 1
                ORDER BY priority
                """)
        .forEach(
            row -> {
              long memeId = longValue(row, "meme_id");
              Entry entry = entries.get(memeId);
              if (entry == null) {
                return;
              }

              Rule rule =
                  new Rule(
                      nullableLong(row, "sense_id"),
                      stringValue(row, "rule_type"),
                      stringValue(row, "rule_value"),
                      nullableString(row, "rule_config"),
                      ((Number) row.get("weight")).doubleValue(),
                      intValue(row, "priority"));
              rules.computeIfAbsent(memeId, ignored -> new ArrayList<>()).add(rule);
              appendLexicalAnchor(entry, rule, anchors);
            });
    return rules;
  }

  private void appendLexicalAnchor(Entry entry, Rule rule, List<Anchor> anchors) {
    if (!LEXICAL_RULE_TYPES.contains(rule.type())) {
      return;
    }

    NormalizationProfile profile =
        switch (rule.type()) {
          case "pinyin_match" -> NormalizationProfile.PINYIN;
          case "exact_match" -> null;
          default -> NormalizationProfile.BASE;
        };
    String value =
        profile == null
            ? rule.value()
            : normalizer.normalize(rule.value(), entry.language(), profile);

    anchors.add(new Anchor(entry.id(), rule.senseId(), value, profile, "rule:" + rule.type()));
    if ("normalized_match".equals(rule.type()) && spacingEligible(rule.value())) {
      anchors.add(
          new Anchor(
              entry.id(),
              rule.senseId(),
              normalizer.normalize(rule.value(), entry.language(), NormalizationProfile.SPACING),
              NormalizationProfile.SPACING,
              "rule:normalized_match:spacing"));
    }
  }

  private static boolean spacingEligible(String value) {
    return value != null
        && value
            .codePoints()
            .anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN);
  }

  private static <T> Map<Long, List<T>> immutableLists(Map<Long, List<T>> source) {
    Map<Long, List<T>> result = new HashMap<>();
    source.forEach((key, value) -> result.put(key, List.copyOf(value)));
    return Map.copyOf(result);
  }

  private static NormalizationProfile profile(String variantType) {
    return switch (variantType) {
      case "pinyin" -> NormalizationProfile.PINYIN;
      case "spacing_variant" -> NormalizationProfile.SPACING;
      default -> NormalizationProfile.BASE;
    };
  }

  private static String stringValue(Map<String, Object> row, String key) {
    return String.valueOf(row.get(key));
  }

  private static String nullableString(Map<String, Object> row, String key) {
    Object value = row.get(key);
    return value == null ? null : String.valueOf(value);
  }

  private static long longValue(Map<String, Object> row, String key) {
    return ((Number) row.get(key)).longValue();
  }

  private static int intValue(Map<String, Object> row, String key) {
    return ((Number) row.get(key)).intValue();
  }

  private static Long nullableLong(Map<String, Object> row, String key) {
    Object value = row.get(key);
    return value instanceof Number number ? number.longValue() : null;
  }

  private static boolean booleanValue(Map<String, Object> row, String key) {
    Object value = row.get(key);
    return value instanceof Boolean bool ? bool : ((Number) value).intValue() == 1;
  }

  public record Data(
      List<Anchor> anchors,
      Map<Long, Entry> entries,
      Map<Long, List<Sense>> senses,
      Map<Long, List<Example>> examples,
      Map<Long, List<Rule>> rules) {}

  public record Anchor(
      long memeId, Long senseId, String value, NormalizationProfile profile, String source) {}

  public record Sense(long id, int no, String definition, String policyOverride) {}

  public record Example(Long senseId, String text) {}

  public record Rule(
      Long senseId, String type, String value, String config, double weight, int priority) {}

  public record Entry(
      long id,
      String code,
      String term,
      String language,
      String status,
      String risk,
      boolean display,
      String moderation) {
    /** Compatibility constructor for tests and integrations compiled against V3.1 internals. */
    public Entry(
        long id,
        String code,
        String term,
        String language,
        String status,
        String risk,
        boolean detect,
        boolean display,
        boolean generate,
        boolean recommend,
        String moderation) {
      this(id, code, term, language, status, risk, display, moderation);
    }
  }
}
