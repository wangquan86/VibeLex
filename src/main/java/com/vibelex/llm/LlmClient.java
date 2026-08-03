package com.vibelex.llm;

public interface LlmClient {
  String protocol();

  String complete(LlmRequest request);
}
