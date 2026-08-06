package com.vibelex.search;

import java.util.List;

public interface EmbeddingProvider {
  List<Float> embed(String text);
}
