package com.vibelex.recognitionv2;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "vibelex.recognition.v2")
public class RecognitionV2Properties {
  private boolean enabled;
  private boolean semanticRecallEnabled;
  private double minConfidence = .6;
  private int maxResults = 20;
  private int sentenceMaxCharacters = 480;
  private boolean fallbackToV1OnSearchFailure = true;
  private boolean fallbackToV1OnEmbeddingFailure = true;
  private Elasticsearch elasticsearch = new Elasticsearch();
  private Embedding embedding = new Embedding();

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean v) {
    enabled = v;
  }

  public boolean isSemanticRecallEnabled() {
    return semanticRecallEnabled;
  }

  public void setSemanticRecallEnabled(boolean v) {
    semanticRecallEnabled = v;
  }

  public double getMinConfidence() {
    return minConfidence;
  }

  public void setMinConfidence(double v) {
    minConfidence = v;
  }

  public int getMaxResults() {
    return maxResults;
  }

  public void setMaxResults(int v) {
    maxResults = v;
  }

  public int getSentenceMaxCharacters() {
    return sentenceMaxCharacters;
  }

  public void setSentenceMaxCharacters(int v) {
    sentenceMaxCharacters = v;
  }

  public boolean isFallbackToV1OnSearchFailure() {
    return fallbackToV1OnSearchFailure;
  }

  public void setFallbackToV1OnSearchFailure(boolean v) {
    fallbackToV1OnSearchFailure = v;
  }

  public boolean isFallbackToV1OnEmbeddingFailure() {
    return fallbackToV1OnEmbeddingFailure;
  }

  public void setFallbackToV1OnEmbeddingFailure(boolean v) {
    fallbackToV1OnEmbeddingFailure = v;
  }

  public Elasticsearch getElasticsearch() {
    return elasticsearch;
  }

  public void setElasticsearch(Elasticsearch v) {
    elasticsearch = v;
  }

  public Embedding getEmbedding() {
    return embedding;
  }

  public void setEmbedding(Embedding v) {
    embedding = v;
  }

  public static class Elasticsearch {
    private boolean enabled;
    private String uris;
    private String indexName;
    private String indexAlias;
    private int connectTimeoutMillis = 1000,
        requestTimeoutMillis = 3000,
        lexicalTopK = 20,
        semanticTopK = 20;
    private double minimumSemanticScore = .65;

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean v) {
      enabled = v;
    }

    public String getUris() {
      return uris;
    }

    public void setUris(String v) {
      uris = v;
    }

    public String getIndexName() {
      return indexName;
    }

    public void setIndexName(String v) {
      indexName = v;
    }

    public String getIndexAlias() {
      return indexAlias;
    }

    public void setIndexAlias(String v) {
      indexAlias = v;
    }

    public int getConnectTimeoutMillis() {
      return connectTimeoutMillis;
    }

    public void setConnectTimeoutMillis(int v) {
      connectTimeoutMillis = v;
    }

    public int getRequestTimeoutMillis() {
      return requestTimeoutMillis;
    }

    public void setRequestTimeoutMillis(int v) {
      requestTimeoutMillis = v;
    }

    public int getLexicalTopK() {
      return lexicalTopK;
    }

    public void setLexicalTopK(int v) {
      lexicalTopK = v;
    }

    public int getSemanticTopK() {
      return semanticTopK;
    }

    public void setSemanticTopK(int v) {
      semanticTopK = v;
    }

    public double getMinimumSemanticScore() {
      return minimumSemanticScore;
    }

    public void setMinimumSemanticScore(double v) {
      minimumSemanticScore = v;
    }
  }

  public static class Embedding {
    private boolean enabled;
    private String endpoint, modelName;
    private int vectorDimension = 1024, connectTimeoutMillis = 1000, requestTimeoutMillis = 5000;
    private String similarity = "cosine";

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean v) {
      enabled = v;
    }

    public String getEndpoint() {
      return endpoint;
    }

    public void setEndpoint(String v) {
      endpoint = v;
    }

    public String getModelName() {
      return modelName;
    }

    public void setModelName(String v) {
      modelName = v;
    }

    public int getVectorDimension() {
      return vectorDimension;
    }

    public void setVectorDimension(int v) {
      vectorDimension = v;
    }

    public int getConnectTimeoutMillis() {
      return connectTimeoutMillis;
    }

    public void setConnectTimeoutMillis(int v) {
      connectTimeoutMillis = v;
    }

    public int getRequestTimeoutMillis() {
      return requestTimeoutMillis;
    }

    public void setRequestTimeoutMillis(int v) {
      requestTimeoutMillis = v;
    }

    public String getSimilarity() {
      return similarity;
    }

    public void setSimilarity(String v) {
      similarity = v;
    }
  }
}
