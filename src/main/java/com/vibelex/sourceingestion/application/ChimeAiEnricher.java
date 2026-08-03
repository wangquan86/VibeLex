package com.vibelex.sourceingestion.application;

import com.vibelex.llm.LlmScenarioProperties;
import com.vibelex.llm.PromptTemplateLoader;
import com.vibelex.llm.ResponsesWebSearchLlmClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Adds verified origin evidence to CHIME records while preserving source examples verbatim. */
@Component
public class ChimeAiEnricher extends BuzzwordAiEnricher {
  public ChimeAiEnricher(
      LlmScenarioProperties properties,
      PromptTemplateLoader prompts,
      ResponsesWebSearchLlmClient client,
      ObjectMapper mapper) {
    super(
        properties,
        prompts,
        client,
        mapper,
        "chime",
        "chime-enrichment",
        "chime-ai-enrichment-v1",
        false);
  }
}
