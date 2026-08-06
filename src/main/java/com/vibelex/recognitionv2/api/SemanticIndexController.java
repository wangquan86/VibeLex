package com.vibelex.recognitionv2.api;

import com.vibelex.search.SearchIndexService;
import com.vibelex.search.SearchIndexRebuildService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SemanticIndexController {
  private final SearchIndexService service;
  private final SearchIndexRebuildService rebuilds;

  public SemanticIndexController(SearchIndexService service, SearchIndexRebuildService rebuilds) {
    this.service = service;
    this.rebuilds = rebuilds;
  }

  @GetMapping({"/api/admin/search/index", "/api/admin/recognition-v2/index"})
  public Map<String, Object> status() {
    Map<String, Object> result = new LinkedHashMap<>(service.status());
    result.put("rebuild", rebuilds.latestStatus());
    return result;
  }

  @PostMapping("/api/admin/search/index/rebuild")
  public Object rebuild() {
    return rebuilds.start();
  }

  @GetMapping("/api/admin/search/index/rebuild/{jobId}")
  public Map<String, Object> rebuildStatus(@PathVariable long jobId) {
    return rebuilds.status(jobId);
  }
}
