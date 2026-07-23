package com.vibelex.shared;

import java.net.URI;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
  @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
  ProblemDetail badRequest(RuntimeException ex) {
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
