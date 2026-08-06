package com.vibelex.search;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "vibelex.search")
public class SearchProperties {
  private Elasticsearch elasticsearch = new Elasticsearch();
  private Embedding embedding = new Embedding();

  public Elasticsearch getElasticsearch() {
    return elasticsearch;
  }

  public void setElasticsearch(Elasticsearch value) {
    elasticsearch = value;
  }

  public Embedding getEmbedding() {
    return embedding;
  }

  public void setEmbedding(Embedding value) {
    embedding = value;
  }

  public static class Elasticsearch {
    private boolean enabled;
    private String uris;
    private String indexName = "vibelex_sense";
    private String indexAlias = "vibelex_sense_current";
    private int connectTimeoutMillis = 1000;
    private int requestTimeoutMillis = 3000;

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean value) {
      enabled = value;
    }

    public String getUris() {
      return uris;
    }

    public void setUris(String value) {
      uris = value;
    }

    public String getIndexName() {
      return indexName;
    }

    public void setIndexName(String value) {
      indexName = value;
    }

    public String getIndexAlias() {
      return indexAlias;
    }

    public void setIndexAlias(String value) {
      indexAlias = value;
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

  public static class Embedding {
    private boolean enabled;
    private String endpoint;
    private String modelName = "bge-large-zh";
    private int vectorDimension = 1024;
    private int connectTimeoutMillis = 3000;
    private int requestTimeoutMillis = 30000;
    private String similarity = "cosine";

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

    public String getModelName() {
      return modelName;
    }

    public void setModelName(String value) {
      modelName = value;
    }

    public int getVectorDimension() {
      return vectorDimension;
    }

    public void setVectorDimension(int value) {
      vectorDimension = value;
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

    public String getSimilarity() {
      return similarity;
    }

    public void setSimilarity(String value) {
      similarity = value;
    }
  }
}
