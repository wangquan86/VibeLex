package com.vibelex.crawling.api;

import com.vibelex.crawling.CrawlExecutionService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/v3/crawl-sources")
public class CrawlController {
  private final CrawlExecutionService service;

  public CrawlController(CrawlExecutionService service) {
    this.service = service;
  }

  @GetMapping
  public List<Map<String, Object>> sources() {
    return service.sources();
  }

  @GetMapping("/{sourceCode}")
  public Map<String, Object> source(@PathVariable String sourceCode) {
    return service.source(sourceCode);
  }

  @PostMapping("/{sourceCode}/sync")
  public Map<String, Object> sync(@PathVariable String sourceCode) {
    return service.startSync(sourceCode);
  }

  @PostMapping("/{sourceCode}/cancel")
  public Map<String, Object> cancel(@PathVariable String sourceCode) {
    return service.cancel(sourceCode);
  }

  @GetMapping("/{sourceCode}/records")
  public Map<String, Object> records(
      @PathVariable String sourceCode,
      @RequestParam(defaultValue = "all") String status,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size) {
    return service.records(sourceCode, status, page, size);
  }

  @GetMapping("/records")
  public Map<String, Object> allRecords(
      @RequestParam(defaultValue = "all") String source,
      @RequestParam(defaultValue = "all") String status,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size) {
    return service.records(source, status, page, size);
  }
}
