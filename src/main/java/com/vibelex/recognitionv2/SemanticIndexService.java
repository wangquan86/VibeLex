package com.vibelex.recognitionv2;

import com.vibelex.search.ElasticsearchGateway;
import com.vibelex.search.EmbeddingProvider;
import com.vibelex.search.SearchIndexService;
import com.vibelex.search.SearchProperties;
import com.vibelex.shared.persistence.MyBatisDatabase;
import tools.jackson.databind.ObjectMapper;

/**
 * @deprecated The sense index is shared by recognition and recommendation since V3.2.
 */
@Deprecated
public class SemanticIndexService extends SearchIndexService {
  public SemanticIndexService(
      MyBatisDatabase database,
      ObjectMapper mapper,
      SearchProperties properties,
      ElasticsearchGateway es,
      EmbeddingProvider embedding) {
    super(database, mapper, properties, es, embedding);
  }
}
