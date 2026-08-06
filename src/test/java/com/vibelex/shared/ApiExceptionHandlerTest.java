package com.vibelex.shared;

import static org.assertj.core.api.Assertions.assertThat;

import com.vibelex.recommendation.application.RecommendationContextTooLongException;
import com.vibelex.recommendation.application.RecommendationUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ApiExceptionHandlerTest {
  private final ApiExceptionHandler handler = new ApiExceptionHandler();

  @Test
  void mapsRecommendationLengthAndAvailabilitySeparately() {
    assertThat(
            handler
                .recommendationContextTooLong(new RecommendationContextTooLongException())
                .getStatus())
        .isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE.value());
    assertThat(
            handler
                .recommendationUnavailable(new RecommendationUnavailableException("不可用"))
                .getStatus())
        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
  }
}
