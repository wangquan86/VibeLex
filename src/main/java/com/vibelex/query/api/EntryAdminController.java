package com.vibelex.query.api;

import com.vibelex.query.application.EntryAdminQueryService;
import com.vibelex.query.application.EntryVariantService;
import com.vibelex.query.application.EntryWithdrawalService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/entries")
public class EntryAdminController {

  private final EntryAdminQueryService service;
  private final EntryVariantService variants;
  private final EntryWithdrawalService withdrawals;

  public EntryAdminController(
      EntryAdminQueryService service,
      EntryVariantService variants,
      EntryWithdrawalService withdrawals) {
    this.service = service;
    this.variants = variants;
    this.withdrawals = withdrawals;
  }

  @GetMapping
  public Map<String, Object> list(
      @RequestParam(defaultValue = "all") String riskLevel,
      @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "") String source,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size) {
    return service.list("published", riskLevel, q, source, page, size);
  }

  @GetMapping("/{id}")
  public Map<String, Object> detail(@PathVariable long id) {
    Map<String, Object> result = service.detail(id);
    result.put("variantGenerationEnabled", variants.isEnabled());
    return result;
  }

  @PostMapping("/{id}/regenerate-variants")
  public Map<String, Object> regenerateVariants(@PathVariable long id) {
    return variants.regenerate(id);
  }

  @PostMapping("/{id}/withdraw")
  public Map<String, Object> withdraw(
      @PathVariable long id, @RequestBody(required = false) WithdrawalRequest request) {
    return withdrawals.withdraw(id, request == null ? null : request.reason());
  }

  @PostMapping("/batch-withdraw")
  public Map<String, Object> withdrawBatch(@RequestBody BatchWithdrawalRequest request) {
    return withdrawals.withdrawBatch(request.ids(), request.reason());
  }

  public record WithdrawalRequest(String reason) {}

  public record BatchWithdrawalRequest(List<Long> ids, String reason) {}
}
