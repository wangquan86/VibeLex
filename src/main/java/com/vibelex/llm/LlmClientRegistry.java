package com.vibelex.llm;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class LlmClientRegistry {
  private final Map<String, LlmClient> clients;

  public LlmClientRegistry(List<LlmClient> clients) {
    this.clients =
        clients.stream()
            .collect(Collectors.toUnmodifiableMap(LlmClient::protocol, Function.identity()));
  }

  public LlmClient client(String protocol) {
    LlmClient client = clients.get(protocol);
    if (client == null) throw new IllegalArgumentException("不支持的 LLM protocol: " + protocol);
    return client;
  }
}
