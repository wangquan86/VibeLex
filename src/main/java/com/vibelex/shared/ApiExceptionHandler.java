package com.vibelex.shared;

import com.vibelex.recognitionv2.RecognitionV2Service;
import com.vibelex.recommendation.application.RecommendationContextTooLongException;
import com.vibelex.recommendation.application.RecommendationUnavailableException;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  @ExceptionHandler(RecognitionV2Service.TextTooLongException.class)
  ProblemDetail textTooLong(RecognitionV2Service.TextTooLongException ex) {
    return problem(HttpStatus.PAYLOAD_TOO_LARGE, "文本超过 V2 识别长度上限");
  }

  @ExceptionHandler(RecommendationContextTooLongException.class)
  ProblemDetail recommendationContextTooLong(RecommendationContextTooLongException ex) {
    return problem(HttpStatus.PAYLOAD_TOO_LARGE, ex.getMessage());
  }

  @ExceptionHandler(RecommendationUnavailableException.class)
  ProblemDetail recommendationUnavailable(RecommendationUnavailableException ex) {
    log.warn("推荐服务不可用: {}", ex.getMessage());
    return problem(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
  }

  @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
  ProblemDetail badRequest(RuntimeException ex) {
    log.error("API 请求处理失败: {}", ex.getMessage(), ex);
    return problem(HttpStatus.BAD_REQUEST, ex.getMessage());
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  ProblemDetail conflict(DataIntegrityViolationException ex) {
    return problem(HttpStatus.CONFLICT, "数据冲突或违反唯一性约束");
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ProblemDetail validation(MethodArgumentNotValidException ex) {
    String detail =
        ex.getBindingResult().getFieldErrors().stream()
            .findFirst()
            .map(e -> e.getField() + ": " + e.getDefaultMessage())
            .orElse("请求参数无效");
    return problem(HttpStatus.BAD_REQUEST, detail);
  }

  private ProblemDetail problem(HttpStatus status, String detail) {
    ProblemDetail p =
        ProblemDetail.forStatusAndDetail(
            status, detail == null ? status.getReasonPhrase() : detail);
    p.setType(URI.create("urn:vibelex:error:" + status.value()));
    return p;
  }
}
