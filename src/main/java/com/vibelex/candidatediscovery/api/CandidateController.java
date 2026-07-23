package com.vibelex.candidatediscovery.api;

import com.vibelex.candidatediscovery.application.CandidateService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/candidates")
public class CandidateController {
  private final CandidateService service;

  public CandidateController(CandidateService service) {
    this.service = service;
  }

  @GetMapping
  public Map<String, Object> list(
      @RequestParam(defaultValue = "editing") String status,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "") String source) {
    return service.list(status, page, size, q, source);
  }

  @GetMapping("/{id}")
  public Map<String, Object> detail(@PathVariable long id) {
    return service.detail(id);
  }

  @PostMapping
  public Map<String, Object> create(@RequestBody CandidateRequest request) {
    return service.create(
        request.term(),
        request.definition(),
        request.category(),
        request.origin(),
        request.examples(),
        request.profanity(),
        request.offense(),
        request.sourceUrl(),
        request.variants());
  }

  @PutMapping("/{id}")
  public Map<String, Object> update(@PathVariable long id, @RequestBody CandidateRequest request) {
    return service.update(
        id,
        request.term(),
        request.definition(),
        request.category(),
        request.origin(),
        request.examples(),
        request.profanity(),
        request.offense(),
        request.sourceUrl(),
        request.variants());
  }

  @PostMapping("/generate-variants")
  public Map<String, Object> generateVariants(@RequestBody VariantGenerationRequest request) {
    return service.generateVariants(request.term(), request.definition(), request.retainedVariants());
  }

  @PostMapping("/{id}/submit")
  public Map<String, Object> submit(@PathVariable long id) {
    return service.submit(id);
  }

  @PostMapping("/batch-submit")
  public Map<String, Object> batchSubmit(@RequestBody BatchSubmitRequest request) {
    return service.batchSubmit(request.ids());
  }

  @PostMapping("/batch-approve")
  public Map<String, Object> batchApprove(@RequestBody BatchReviewRequest request) {
    return service.batchApprove(request.ids(), request.comment());
  }

  @PostMapping("/batch-return")
  public Map<String, Object> batchReturn(@RequestBody BatchReviewRequest request) {
    return service.batchReturn(request.ids(), request.comment());
  }

  @PostMapping("/{id}/return")
  public Map<String, Object> returnForEditing(
      @PathVariable long id, @RequestBody ReviewRequest request) {
    return service.returnForEditing(id, request.comment());
  }

  @PostMapping("/{id}/approve")
  public Map<String, Object> approve(
      @PathVariable long id, @RequestBody(required = false) ReviewRequest request) {
    return service.approve(id, request == null ? null : request.comment());
  }

  public record CandidateRequest(
      String term,
      String definition,
      String category,
      String origin,
      List<String> examples,
      boolean profanity,
      boolean offense,
      String sourceUrl,
      List<VariantRequest> variants) {}

  public record VariantGenerationRequest(
      String term, String definition, List<VariantRequest> retainedVariants) {}

  public record VariantRequest(
      String variant,
      String variantType,
      Double confidence,
      String sourceMethod,
      List<EvidenceRequest> evidence) {}

  public record EvidenceRequest(String url, String title, String snippet) {}

  public record ReviewRequest(String comment) {}

  public record BatchSubmitRequest(List<Long> ids) {}

  public record BatchReviewRequest(List<Long> ids, String comment) {}
}
