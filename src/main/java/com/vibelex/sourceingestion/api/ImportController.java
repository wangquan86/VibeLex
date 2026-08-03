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

  @GetMapping("/{runId}/records")
  public Map<String, Object> records(
      @PathVariable long runId,
      @RequestParam(defaultValue = "all") String status,
      @RequestParam(defaultValue = "") String query,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size) {
    return service.records(runId, status, query, page, size);
  }

  @GetMapping("/{runId}/records/{recordId}")
  public Map<String, Object> record(@PathVariable long runId, @PathVariable long recordId) {
    return service.record(runId, recordId);
  }

  @PostMapping("/{runId}/retry")
  public Map<String, Object> retry(
      @PathVariable long runId, @RequestParam(required = false) Long recordId) {
    return Map.of("retriedCount", service.retryFailed(runId, recordId));
  }

  @PostMapping("/{runId}/cancel")
  public Map<String, Object> cancel(@PathVariable long runId) {
    return service.cancel(runId);
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
