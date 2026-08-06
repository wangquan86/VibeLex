package com.vibelex.recommendation.application;

import jakarta.annotation.PostConstruct;
import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "vibelex.recommendation.v3")
public class RecommendationProperties {
  private boolean enabled;
  private int maxContextCharacters = 480;
  private int defaultMaxResults = 10;
  private int maxResultsLimit = 20;
  private int semanticTopK = 50;
  private int lexicalTopK = 50;
  private double minimumSemanticScore = .65;
  private int rrfRankConstant = 60;
  private double semanticWeight = .7;
  private double lexicalWeight = .3;
  private Origin origin = new Origin();
  private Reranker reranker = new Reranker();

  @PostConstruct
  void validate() {
    if (maxContextCharacters <= 0 || defaultMaxResults <= 0 || maxResultsLimit <= 0)
      throw new IllegalStateException("推荐长度和数量上限必须为正数");
    if (defaultMaxResults > maxResultsLimit) throw new IllegalStateException("推荐默认返回数不能超过返回上限");
    if (semanticTopK < maxResultsLimit || lexicalTopK < maxResultsLimit)
      throw new IllegalStateException("推荐召回 Top K 不能小于最大返回数");
    if (rrfRankConstant <= 0) throw new IllegalStateException("RRF 排名常数必须为正数");
    if (semanticWeight < 0 || lexicalWeight < 0) throw new IllegalStateException("推荐权重不能为负数");
    if (Math.abs(semanticWeight + lexicalWeight - 1d) > 0.000001d)
      throw new IllegalStateException("推荐语义和词法权重之和必须为 1");
    if (minimumSemanticScore < 0 || minimumSemanticScore > 1)
      throw new IllegalStateException("推荐语义最低分必须在 0 到 1 之间");
    origin.validate();
    reranker.validate();
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean value) {
    enabled = value;
  }

  public int getMaxContextCharacters() {
    return maxContextCharacters;
  }

  public void setMaxContextCharacters(int value) {
    maxContextCharacters = value;
  }

  public int getDefaultMaxResults() {
    return defaultMaxResults;
  }

  public void setDefaultMaxResults(int value) {
    defaultMaxResults = value;
  }

  public int getMaxResultsLimit() {
    return maxResultsLimit;
  }

  public void setMaxResultsLimit(int value) {
    maxResultsLimit = value;
  }

  public int getSemanticTopK() {
    return semanticTopK;
  }

  public void setSemanticTopK(int value) {
    semanticTopK = value;
  }

  public int getLexicalTopK() {
    return lexicalTopK;
  }

  public void setLexicalTopK(int value) {
    lexicalTopK = value;
  }

  public double getMinimumSemanticScore() {
    return minimumSemanticScore;
  }

  public void setMinimumSemanticScore(double value) {
    minimumSemanticScore = value;
  }

  public int getRrfRankConstant() {
    return rrfRankConstant;
  }

  public void setRrfRankConstant(int value) {
    rrfRankConstant = value;
  }

  public double getSemanticWeight() {
    return semanticWeight;
  }

  public void setSemanticWeight(double value) {
    semanticWeight = value;
  }

  public double getLexicalWeight() {
    return lexicalWeight;
  }

  public void setLexicalWeight(double value) {
    lexicalWeight = value;
  }

  public Origin getOrigin() {
    return origin;
  }

  public void setOrigin(Origin value) {
    origin = value;
  }

  public Reranker getReranker() {
    return reranker;
  }

  public void setReranker(Reranker value) {
    reranker = value;
  }

  public static class Origin {
    private boolean enabled = true;
    private int maxEvidencePerItem = 10;

    void validate() {
      if (maxEvidencePerItem <= 0)
        throw new IllegalStateException("Recommendation origin evidence limit must be positive");
    }

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean value) {
      enabled = value;
    }

    public int getMaxEvidencePerItem() {
      return maxEvidencePerItem;
    }

    public void setMaxEvidencePerItem(int value) {
      maxEvidencePerItem = value;
    }
  }

  public static class Reranker {
    private boolean enabled = true;
    private String endpoint = "http://10.145.12.11:8082";
    private int connectTimeoutMillis = 1000;
    private int requestTimeoutMillis = 10000;

    void validate() {
      if (connectTimeoutMillis <= 0 || requestTimeoutMillis <= 0)
        throw new IllegalStateException("Reranker 连接和请求超时必须为正数");
      if (endpoint == null || endpoint.isBlank())
        throw new IllegalStateException("Reranker endpoint 不能为空");
      try {
        URI uri = URI.create(endpoint.trim());
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
            || uri.getHost() == null)
          throw new IllegalArgumentException();
      } catch (IllegalArgumentException exception) {
        throw new IllegalStateException("Reranker endpoint 必须是有效的 HTTP 地址", exception);
      }
    }

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean value) {
      enabled = value;
    }

    public String getEndpoint() {
      return endpoint;
    }

    public void setEndpoint(String value) {
      endpoint = value;
    }

    public int getConnectTimeoutMillis() {
      return connectTimeoutMillis;
    }

    public void setConnectTimeoutMillis(int value) {
      connectTimeoutMillis = value;
    }

    public int getRequestTimeoutMillis() {
      return requestTimeoutMillis;
    }

    public void setRequestTimeoutMillis(int value) {
      requestTimeoutMillis = value;
    }
  }
}
