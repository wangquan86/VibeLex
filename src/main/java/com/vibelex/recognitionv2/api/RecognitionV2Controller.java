package com.vibelex.recognitionv2.api;

import com.vibelex.recognitionv2.RecognitionV2Service;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/recognitions")
public class RecognitionV2Controller {
  private final RecognitionV2Service service;

  public RecognitionV2Controller(RecognitionV2Service service) {
    this.service = service;
  }

  @PostMapping
  public Map<String, Object> recognize(@Valid @RequestBody RecognitionV2Service.Request request) {
    return service.recognize(request);
  }
}
