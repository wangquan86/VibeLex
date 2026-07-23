package com.vibelex.llm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

@Component
public class PromptTemplateLoader {
  private final ResourceLoader resources;

  public PromptTemplateLoader(ResourceLoader resources) {
    this.resources = resources;
  }

  public String load(String location) {
    if (location == null || location.isBlank()) throw new IllegalArgumentException("LLM 提示词文件未配置");
    Resource resource = resources.getResource(location);
    try (var input = resource.getInputStream()) {
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("无法读取 LLM 提示词文件: " + location, e);
    }
  }
}
