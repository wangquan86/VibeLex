package com.vibelex.recognitionv2.api;

import com.vibelex.recognitionv2.IndexSyncTaskService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/recognition-v2/index/tasks")
public class IndexSyncTaskController {
  private final IndexSyncTaskService service;

  public IndexSyncTaskController(IndexSyncTaskService service) {
    this.service = service;
  }

  @GetMapping
  public Map<String, Object> list(
      @RequestParam(defaultValue = "all") String status,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size) {
    return service.list(status, page, size);
  }

  @PostMapping("/{id}/retry")
  public Map<String, Object> retry(@PathVariable long id) {
    service.retry(id);
    return Map.of("id", id, "status", "pending");
  }
}
