package com.vibelex.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import tools.jackson.databind.ObjectMapper;

class LlmClientSpringWiringTest {
  @Test
  void wiresProductionConstructorsWithoutDefaultConstructors() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context.registerBean(ObjectMapper.class, (Supplier<ObjectMapper>) ObjectMapper::new);
      context.registerBean(
          LlmScenarioProperties.class,
          (Supplier<LlmScenarioProperties>) LlmScenarioProperties::new);
      context.register(
          OpenAiChatCompletionsClient.class,
          ResponsesWebSearchLlmClient.class,
          LlmClientRegistry.class);
      context.refresh();

      LlmClientRegistry registry = context.getBean(LlmClientRegistry.class);
      assertThat(registry.client("chat-completions"))
          .isInstanceOf(OpenAiChatCompletionsClient.class);
      assertThat(registry.client("responses")).isInstanceOf(ResponsesWebSearchLlmClient.class);
    }
  }
}
