package com.vibelex.sourceingestion.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class ChimeImporterTest {
  @TempDir Path tempDir;

  @Test
  void parsesChimeDictionaryRecords() throws Exception {
    Path file = tempDir.resolve("chime.json");
    Files.writeString(
        file,
        """
        [{"meme":"破防了","meaning":"情绪受到强烈触动","origin":"网络评论","examples":["看到这里真的破防了"]}]
        """);

    CandidateImporter.ImportedBatch batch = new ChimeImporter(new ObjectMapper()).parse(file);

    assertThat(batch.totalCount()).isEqualTo(1);
    assertThat(batch.errors()).isEmpty();
    assertThat(batch.candidates())
        .singleElement()
        .satisfies(
            candidate -> {
              assertThat(candidate.term()).isEqualTo("破防了");
              assertThat(candidate.definition()).isEqualTo("情绪受到强烈触动");
              assertThat(candidate.processingNote().get("examples")).asList().contains("看到这里真的破防了");
            });
  }
}
