package com.vibelex.recognition.api;

import com.vibelex.recognition.application.RecognitionService;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/recognize")
public class RecognitionController {
  private final RecognitionService service;

  public RecognitionController(RecognitionService service) {
    this.service = service;
  }

  @PostMapping
  public Map<String, Object> recognize(@Valid @RequestBody RecognitionService.Request request) {
    return service.recognize(request);
  }
}
