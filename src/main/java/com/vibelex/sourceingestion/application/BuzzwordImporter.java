package com.vibelex.sourceingestion.application;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Parses the local SCUNLP/Buzzword dictionary export. */
@Component
public class BuzzwordImporter implements CandidateImporter {
  private static final String SOURCE_URL = "https://github.com/SCUNLP/Buzzword";
  private static final String FILE_NAME = "buzzword.json";
  private final ObjectMapper mapper;

  public BuzzwordImporter(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public String sourceCode() {
    return "buzzword";
  }

  @Override
  public String sourceName() {
    return "Buzzword";
  }

  @Override
  public String sourceUrl() {
    return SOURCE_URL;
  }

  @Override
  public String parserVersion() {
    return "buzzword-json";
  }

  @Override
  public boolean supportsFileName(String fileName) {
    return FILE_NAME.equalsIgnoreCase(fileName);
  }

  @Override
  public ImportedBatch parse(Path file) {
    JsonNode root = mapper.readTree(file.toFile());
    if (!root.isArray())
      throw new IllegalArgumentException("Buzzword file root must be a JSON array");

    List<ImportedCandidate> candidates = new ArrayList<>();
    List<String> errors = new ArrayList<>();
    int rejected = 0;
    for (int index = 0; index < root.size(); index++) {
      try {
        JsonNode item = root.get(index);
        if (item == null || !item.isObject())
          throw new IllegalArgumentException("record must be an object");
        String term = requiredText(text(item, "term"), "term", index);
        String definition = requiredText(text(item, "ground_truth"), "ground_truth", index);
        Map<String, Object> note = new LinkedHashMap<>();
        note.put("examples", examples(item));
        candidates.add(new ImportedCandidate(index, term, term, definition, SOURCE_URL, note));
      } catch (RuntimeException e) {
        rejected++;
        errors.add("#" + index + ": " + e.getMessage());
      }
    }
    return new ImportedBatch(root.size(), rejected, candidates, errors);
  }

  private List<String> examples(JsonNode item) {
    List<String> result = new ArrayList<>();
    if (!item.path("examples").isArray()) return result;
    for (JsonNode example : item.path("examples")) {
      if (example.isTextual() && !example.asText().isBlank()) {
        result.add(example.asText());
      }
    }
    return result;
  }

  private String requiredText(String value, String field, int index) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("record " + index + " is missing " + field);
    }
    return value.trim();
  }

  private String text(JsonNode item, String field) {
    JsonNode value = item.get(field);
    return value == null || value.isNull() || !value.isTextual() ? null : value.asText();
  }
}
