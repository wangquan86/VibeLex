package com.vibelex.reviewworkflow.api;

import com.vibelex.reviewworkflow.application.ChangeSetService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/admin/change-sets")
public class ChangeSetController {
  private final ChangeSetService service;

  public ChangeSetController(ChangeSetService service) {
    this.service = service;
  }

  @GetMapping
  public Map<String, Object> list(
      @RequestParam(defaultValue = "pending_review") String status,
      @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size) {
    return service.list(status, q, page, size);
  }

  @GetMapping("/{id}")
  public Map<String, Object> get(@PathVariable long id) {
    return service.detail(id);
  }

  @PostMapping
  public Map<String, Object> create(@Valid @RequestBody CreateRequest r) {
    return service.create(
        r.memeId(), r.changeType(), r.baseVersion(), r.snapshot(), r.changeSummary());
  }

  @PutMapping("/{id}")
  public void update(@PathVariable long id, @Valid @RequestBody UpdateRequest r) {
    service.update(id, r.snapshot(), r.changeSummary());
  }

  @PostMapping("/{id}/submit")
  public void submit(@PathVariable long id) {
    service.transition(id, "submit", null);
  }

  @PostMapping("/{id}/withdraw")
  public void withdraw(@PathVariable long id) {
    service.transition(id, "withdraw", null);
  }

  @PostMapping("/{id}/reject")
  public void reject(@PathVariable long id, @RequestBody CommentRequest r) {
    service.transition(id, "reject", r.comment());
  }

  @PostMapping("/{id}/reopen")
  public void reopen(@PathVariable long id) {
    service.transition(id, "reopen", null);
  }

  @PostMapping("/{id}/approve")
  public Map<String, Object> approve(
      @PathVariable long id, @RequestBody(required = false) CommentRequest r) {
    return service.approve(id, r == null ? null : r.comment());
  }

  public record CreateRequest(
      Long memeId,
      String changeType,
      Integer baseVersion,
      @NotNull JsonNode snapshot,
      String changeSummary) {}

  public record UpdateRequest(@NotNull JsonNode snapshot, String changeSummary) {}

  public record CommentRequest(String comment) {}
}
