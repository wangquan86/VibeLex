package com.vibelex.llm;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "vibelex.llm")
public class LlmScenarioProperties {
  private Map<String, Provider> providers = new LinkedHashMap<>();
  private Map<String, Scenario> scenarios = new LinkedHashMap<>();

  public Map<String, Provider> getProviders() {
    return providers;
  }

  public void setProviders(Map<String, Provider> providers) {
    this.providers = providers == null ? new LinkedHashMap<>() : new LinkedHashMap<>(providers);
  }

  public Provider provider(String name) {
    Provider provider = providers.get(name);
    if (provider == null) throw new IllegalArgumentException("未配置 LLM provider: " + name);
    return provider;
  }

  public Map<String, Scenario> getScenarios() {
    return scenarios;
  }

  public void setScenarios(Map<String, Scenario> scenarios) {
    this.scenarios = scenarios == null ? new LinkedHashMap<>() : new LinkedHashMap<>(scenarios);
  }

  public Scenario scenario(String name) {
    Scenario scenario = scenarios.get(name);
    if (scenario == null) throw new IllegalArgumentException("未配置 LLM 场景: " + name);
    return scenario;
  }

  public static class Scenario {
    private boolean enabled;
    private String provider;
    private String prompt;
    private BigDecimal temperature = new BigDecimal("0.2");
    private int webSearchMaxKeyword = 3;
    private boolean webSearchEnabled;
    private int maximumSourceCharacters = 12000;
    private BigDecimal minimumConfidence = new BigDecimal("0.6");

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public String getProvider() {
      return provider;
    }

    public void setProvider(String provider) {
      this.provider = provider;
    }

    public String getPrompt() {
      return prompt;
    }

    public void setPrompt(String prompt) {
      this.prompt = prompt;
    }

    public BigDecimal getTemperature() {
      return temperature;
    }

    public void setTemperature(BigDecimal temperature) {
      this.temperature = temperature;
    }

    public int getWebSearchMaxKeyword() {
      return webSearchMaxKeyword;
    }

    public void setWebSearchMaxKeyword(int webSearchMaxKeyword) {
      this.webSearchMaxKeyword = webSearchMaxKeyword;
    }

    public boolean isWebSearchEnabled() {
      return webSearchEnabled;
    }

    public void setWebSearchEnabled(boolean webSearchEnabled) {
      this.webSearchEnabled = webSearchEnabled;
    }

    public int getMaximumSourceCharacters() {
      return maximumSourceCharacters;
    }

    public void setMaximumSourceCharacters(int maximumSourceCharacters) {
      this.maximumSourceCharacters = maximumSourceCharacters;
    }

    public BigDecimal getMinimumConfidence() {
      return minimumConfidence;
    }

    public void setMinimumConfidence(BigDecimal minimumConfidence) {
      this.minimumConfidence = minimumConfidence;
    }
  }

  public static class Provider {
    private String protocol;
    private String baseUrl;
    private String apiKey;
    private String model;
    private int requestTimeoutSeconds = 90;

    public String getProtocol() {
      return protocol;
    }

    public void setProtocol(String protocol) {
      this.protocol = protocol;
    }

    public String getBaseUrl() {
      return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
      this.baseUrl = baseUrl;
    }

    public String getApiKey() {
      return apiKey;
    }

    public void setApiKey(String apiKey) {
      this.apiKey = apiKey;
    }

    public String getModel() {
      return model;
    }

    public void setModel(String model) {
      this.model = model;
    }

    public int getRequestTimeoutSeconds() {
      return requestTimeoutSeconds;
    }

    public void setRequestTimeoutSeconds(int requestTimeoutSeconds) {
      this.requestTimeoutSeconds = requestTimeoutSeconds;
    }
  }
}
