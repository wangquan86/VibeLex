package com.vibelex.recommendation.application;

public class RecommendationUnavailableException extends RuntimeException {
  public RecommendationUnavailableException(String message) {
    super(message);
  }

  public RecommendationUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
