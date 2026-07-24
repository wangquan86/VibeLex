package com.vibelex.query.application;

import com.vibelex.actorcontext.CurrentActorProvider;
import com.vibelex.candidatediscovery.domain.TermNormalizer;
import com.vibelex.lexicon.application.LexiconSnapshotService;
import com.vibelex.llm.AiVariantGenerator;
import com.vibelex.reviewworkflow.application.ChangeSetService;
import com.vibelex.shared.persistence.MyBatisDatabase;
import java.util.HashSet;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Service
public class EntryVariantService {
  private final AiVariantGenerator generator;
  private final LexiconSnapshotService snapshots;
  private final ChangeSetService publishing;
  private final CurrentActorProvider actor;
  private final TermNormalizer normalizer;
  private final MyBatisDatabase database;

  public EntryVariantService(
      AiVariantGenerator generator,
      LexiconSnapshotService snapshots,
      ChangeSetService publishing,
      CurrentActorProvider actor,
      TermNormalizer normalizer,
      MyBatisDatabase database) {
    this.generator = generator;
    this.snapshots = snapshots;
    this.publishing = publishing;
    this.actor = actor;
    this.normalizer = normalizer;
    this.database = database;
  }

  public boolean isEnabled() {
    return true;
  }

  @Transactional
  public java.util.Map<String, Object> regenerate(long memeId) {
    ObjectNode snapshot = snapshots.snapshot(memeId);
    ObjectNode entry = (ObjectNode) snapshot.path("meme_entry");
    if (!"published".equals(entry.path("status").asText())) {
      throw new IllegalStateException("只有已发布词条可以重新生成 AI 变体");
    }
    String term = entry.path("canonical_term").asText();
    String canonicalNormalized = entry.path("normalized_term").asText();
    String definition = snapshot.path("senses").path(0).path("definition").asText();
    ArrayNode variants = snapshot.withArray("variants");
    ArrayNode retained = snapshot.arrayNode();
    Set<String> existing = new HashSet<>();
    for (JsonNode variant : variants) {
      if ("ai_suggested".equals(variant.path("source_method").asText())) continue;
      retained.add(variant);
      String value = variant.path("variant").asText();
      String type = variant.path("variant_type").asText();
      if (!value.isBlank() && !type.isBlank()) {
        existing.add(
            type
                + "\u0000"
                + normalizer.normalize(value, "zh-CN", normalizer.profileForVariant(type)));
      }
    }
    for (AiVariantGenerator.GeneratedVariant variant : generator.generate(term, definition)) {
      if (normalizer.normalize(variant.variant(), "zh-CN").equals(canonicalNormalized)) continue;
      String normalized =
          normalizer.normalize(
              variant.variant(), "zh-CN", normalizer.profileForVariant(variant.variantType()));
      String key = variant.variantType() + "\u0000" + normalized;
      if (existing.add(key) && !publishedVariantExistsElsewhere(memeId, normalized)) {
        retained
            .addObject()
            .put("variant", variant.variant())
            .put("variant_type", variant.variantType())
            .put("confidence", variant.confidence())
            .put("source_method", "ai_suggested")
            .put("status", "active");
        appendVariantEvidence(snapshot.withArray("evidence"), variant);
      }
    }
    snapshot.set("variants", retained);
    return publishing.publishCandidate(
        memeId,
        entry.path("current_version").asInt(),
        snapshot,
        "重新生成 AI 词形变体",
        actor.currentActor(),
        true);
  }

  private boolean publishedVariantExistsElsewhere(long memeId, String normalizedVariant) {
    Number count =
        (Number)
            database.scalar(
                """
                SELECT COUNT(*)
                FROM meme_entries e
                LEFT JOIN meme_variants v ON v.meme_id = e.id AND v.status = 'active'
                WHERE e.status = 'published'
                  AND e.id <> ?
                  AND (e.normalized_term = ? OR v.normalized_variant = ?)
                """,
                memeId,
                normalizedVariant,
                normalizedVariant);
    return count != null && count.longValue() > 0;
  }

  private void appendVariantEvidence(
      ArrayNode evidence, AiVariantGenerator.GeneratedVariant variant) {
    Set<String> existingUrls = evidenceUrls(evidence);
    for (AiVariantGenerator.SearchEvidence item : variant.evidence()) {
      if (!existingUrls.add(item.url())) continue;
      evidence
          .addObject()
          .put("source_layer", "explanation")
          .put("source_name", item.title().isBlank() ? "联网搜索" : item.title())
          .put("source_url", item.url())
          .put("evidence_role", "variant")
          .put("evidence_note", item.snippet())
          .put("confidence", variant.confidence())
          .put("status", "active");
    }
  }

  private Set<String> evidenceUrls(ArrayNode evidence) {
    Set<String> urls = new HashSet<>();
    for (JsonNode item : evidence) {
      String url = item.path("source_url").asText().trim();
      if (!url.isBlank()) urls.add(url);
    }
    return urls;
  }
}
