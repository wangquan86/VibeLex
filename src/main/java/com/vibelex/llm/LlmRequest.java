package com.vibelex.llm;

import java.math.BigDecimal;

public record LlmRequest(
    String model,
    String systemPrompt,
    String userContent,
    BigDecimal temperature,
    int requestTimeoutSeconds) {}
