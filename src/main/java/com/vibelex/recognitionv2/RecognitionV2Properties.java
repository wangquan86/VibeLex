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
  private int lexicalTopK = 20;
  private int semanticTopK = 20;
  private double minimumSemanticScore = .65;
  private boolean fallbackToV1OnSearchFailure = true;
  private boolean fallbackToV1OnEmbeddingFailure = true;

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

  public int getLexicalTopK() {
    return lexicalTopK;
  }

  public void setLexicalTopK(int value) {
    lexicalTopK = value;
  }

  public int getSemanticTopK() {
    return semanticTopK;
  }

  public void setSemanticTopK(int value) {
    semanticTopK = value;
  }

  public double getMinimumSemanticScore() {
    return minimumSemanticScore;
  }

  public void setMinimumSemanticScore(double value) {
    minimumSemanticScore = value;
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
}
