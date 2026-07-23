package com.vibelex.lexicon.application;

import com.vibelex.shared.persistence.MyBatisDatabase;
import java.util.Map;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Service
public class LexiconSnapshotService {
  private final MyBatisDatabase database;
  private final ObjectMapper mapper;

  public LexiconSnapshotService(MyBatisDatabase database, ObjectMapper mapper) {
    this.database = database;
    this.mapper = mapper;
  }

  /** 从权威业务表实时构建不可变版本快照。 */
  public ObjectNode snapshot(long memeId) {
    ObjectNode root = mapper.createObjectNode();
    root.put("schema_version", "1.0");
    root.set("meme_entry", row("SELECT * FROM meme_entries WHERE id=?", memeId));
    root.set("senses", rows("SELECT * FROM meme_senses WHERE meme_id=? ORDER BY sense_no", memeId));
    root.set("variants", rows("SELECT * FROM meme_variants WHERE meme_id=? ORDER BY id", memeId));
    root.set("examples", rows("SELECT * FROM meme_examples WHERE meme_id=? ORDER BY id", memeId));
    root.set(
        "match_rules",
        rows("SELECT * FROM meme_match_rules WHERE meme_id=? ORDER BY priority,id", memeId));
    ObjectNode safety = rowOrNull("SELECT * FROM meme_safety_policies WHERE meme_id = ?", memeId);
    root.set("safety_policy", safety == null ? mapper.nullNode() : safety);
    root.set("evidence", rows("SELECT * FROM meme_evidence WHERE meme_id = ? ORDER BY id", memeId));
    return root;
  }

  private ObjectNode row(String sql, long id) {
    ObjectNode node = rowOrNull(sql, id);
    if (node == null) {
      throw new IllegalArgumentException("词条不存在");
    }
    return node;
  }

  private ObjectNode rowOrNull(String sql, long id) {
    Map<String, Object> data = database.optionalOne(sql, id);
    return data == null ? null : mapper.valueToTree(data);
  }

  private ArrayNode rows(String sql, long id) {
    ArrayNode result = mapper.createArrayNode();
    database.list(sql, id).forEach(row -> result.add(mapper.valueToTree(row)));
    return result;
  }
}
