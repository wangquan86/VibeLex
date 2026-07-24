package com.vibelex.recognitionv2.api;

import com.vibelex.recognitionv2.SemanticIndexService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/recognition-v2/index")
public class SemanticIndexController {
  private final SemanticIndexService service;

  public SemanticIndexController(SemanticIndexService service) {
    this.service = service;
  }

  @GetMapping
  public Map<String, Object> status() {
    return service.status();
  }

  @PostMapping("/rebuild")
  public Map<String, Object> rebuild() {
    service.rebuildAll();
    return Map.of("accepted", true);
  }
}
