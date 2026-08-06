package com.vibelex.recommendation.application;

public class RecommendationContextTooLongException extends IllegalArgumentException {
  public RecommendationContextTooLongException() {
    super("推荐上下文超过长度上限");
  }
}
