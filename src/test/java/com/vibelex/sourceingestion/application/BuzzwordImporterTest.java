package com.vibelex.sourceingestion.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class BuzzwordImporterTest {
  @TempDir Path tempDir;

  @Test
  void parsesArrayRecordsAndRetainsAllExamplesWithoutTruncation() throws Exception {
    Path file = tempDir.resolve("Buzzword.json");
    Files.writeString(
        file,
        """
        [{"term":"破防","definition":"简短释义","examples":["例句一","例句二","例句三","例句四","例句五","例句六😀"],"ground_truth":"情绪受到强烈触动"}]
        """);

    CandidateImporter.ImportedBatch batch = new BuzzwordImporter(new ObjectMapper()).parse(file);

    assertThat(batch.totalCount()).isEqualTo(1);
    assertThat(batch.rejectedCount()).isZero();
    assertThat(batch.candidates())
        .singleElement()
        .satisfies(
            candidate -> {
              assertThat(candidate.sourceRecordKey()).isEqualTo("破防");
              assertThat(candidate.term()).isEqualTo("破防");
              assertThat(candidate.definition()).isEqualTo("情绪受到强烈触动");
              assertThat(candidate.processingNote().get("examples"))
                  .asList()
                  .containsExactly("例句一", "例句二", "例句三", "例句四", "例句五", "例句六😀");
            });
  }

  @Test
  void supportsOnlyTheConfiguredBuzzwordFile() {
    BuzzwordImporter importer = new BuzzwordImporter(new ObjectMapper());

    assertThat(importer.supportsFileName("Buzzword.json")).isTrue();
    assertThat(importer.supportsFileName("chime_full.json")).isFalse();
  }
}
