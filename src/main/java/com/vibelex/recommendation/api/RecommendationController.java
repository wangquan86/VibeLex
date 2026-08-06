package com.vibelex.recommendation.api;

import com.vibelex.recommendation.application.RecommendationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v3/recommendations")
public class RecommendationController {
  private final RecommendationService service;

  public RecommendationController(RecommendationService service) {
    this.service = service;
  }

  @PostMapping
  public RecommendationModels.Response recommend(
      @Valid @RequestBody RecommendationModels.Request request) {
    return service.recommend(request);
  }
}
