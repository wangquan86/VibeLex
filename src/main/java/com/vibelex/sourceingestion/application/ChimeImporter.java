package com.vibelex.sourceingestion.application;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class ChimeImporter implements CandidateImporter {
  private static final String SOURCE_URL = "https://github.com/yuboxie/chime";
  private final ObjectMapper mapper;

  public ChimeImporter(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public String sourceCode() {
    return "chime";
  }

  @Override
  public String sourceName() {
    return "CHIME";
  }

  @Override
  public String sourceUrl() {
    return SOURCE_URL;
  }

  @Override
  public String parserVersion() {
    return "chime-json";
  }

  @Override
  public boolean supportsFileName(String fileName) {
    return "chime_full.json".equalsIgnoreCase(fileName);
  }

  @Override
  public ImportedBatch parse(Path file) {
    JsonNode root = mapper.readTree(file.toFile());
    if (!root.isArray()) throw new IllegalArgumentException("CHIME 文件根节点必须是 JSON 数组");
    List<ImportedCandidate> candidates = new ArrayList<>();
    List<String> errors = new ArrayList<>();
    int rejected = 0;
    for (int i = 0; i < root.size(); i++) {
      try {
        JsonNode item = root.get(i);
        String term = requiredText(item, "meme", i);
        Map<String, Object> note = new LinkedHashMap<>();
        note.put("origin", clip(text(item, "origin"), 500));
        note.put("examples", examples(item));
        note.put("profanity", item.path("profanity").asBoolean(false));
        note.put("offense", item.path("offense").asBoolean(false));
        note.put("type_cn", text(item, "type_cn"));
        note.put("type_en", text(item, "type_en"));
        candidates.add(
            new ImportedCandidate(i, null, term, text(item, "meaning"), SOURCE_URL, note));
      } catch (RuntimeException e) {
        rejected++;
        errors.add("#" + i + ": " + e.getMessage());
      }
    }
    return new ImportedBatch(root.size(), rejected, candidates, errors);
  }

  private List<String> examples(JsonNode item) {
    List<String> result = new ArrayList<>();
    if (item.path("examples").isArray()) {
      for (JsonNode example : item.path("examples")) {
        if (example.isTextual()) result.add(example.asText());
      }
    }
    return result;
  }

  private String requiredText(JsonNode item, String field, int index) {
    String value = text(item, field);
    if (value == null || value.isBlank())
      throw new IllegalArgumentException("记录 " + index + " 缺少 " + field);
    return value.trim();
  }

  private String text(JsonNode item, String field) {
    JsonNode value = item.get(field);
    return value == null || value.isNull() ? null : value.asText();
  }

  private String clip(String value, int max) {
    return value == null ? null : value.substring(0, Math.min(max, value.length()));
  }
}
