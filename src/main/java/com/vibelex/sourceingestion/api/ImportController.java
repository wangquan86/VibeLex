package com.vibelex.sourceingestion.api;

import com.vibelex.sourceingestion.application.SourceImportService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/imports")
public class ImportController {
  private final SourceImportService service;

  public ImportController(SourceImportService service) {
    this.service = service;
  }

  @GetMapping("/files")
  public List<String> files(@RequestParam(defaultValue = "chime") String source) {
    return service.availableFiles(source);
  }

  @GetMapping("/sources")
  public List<Map<String, String>> sources() {
    return service.sources();
  }

  @GetMapping("/source-dictionary")
  public List<String> sourceDictionary() {
    return service.sourceDictionary();
  }

  @GetMapping
  public List<Map<String, Object>> runs() {
    return service.runs();
  }

  @GetMapping("/summary")
  public Map<String, Object> summary() {
    return service.summary();
  }

  @PostMapping("/{source}")
  public Map<String, Object> importSource(
      @PathVariable String source, @Valid @RequestBody ImportRequest request) {
    return service.importFile(source, request);
  }

  public record ImportRequest(
      @NotBlank String fileName,
      String sourceVersion,
      String sourceCommit,
      String licenseStatus,
      String licenseSnapshot,
      String upstreamRightsNote,
      String licenseCheckedBy) {}
}
