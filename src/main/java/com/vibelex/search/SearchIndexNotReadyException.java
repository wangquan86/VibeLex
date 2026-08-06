package com.vibelex.search;

/**
 * Indicates a deployment/configuration state that should pause, rather than exhaust, sync tasks.
 */
public class SearchIndexNotReadyException extends IllegalStateException {
  public SearchIndexNotReadyException(String message) {
    super(message);
  }
}
