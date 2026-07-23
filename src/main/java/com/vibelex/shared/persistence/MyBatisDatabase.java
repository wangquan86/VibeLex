package com.vibelex.shared.persistence;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 面向领域服务的轻量 MyBatis 门面。
 *
 * <p>该类统一处理空结果、单值读取和自增主键，不承载跨领域查询或业务规则。
 */
@Component
public class MyBatisDatabase {

  private final VibeLexSqlMapper mapper;

  public MyBatisDatabase(VibeLexSqlMapper mapper) {
    this.mapper = mapper;
  }

  public List<Map<String, Object>> list(String sql, Object... args) {
    return mapper.selectList(SqlCommand.of(sql, args));
  }

  public Map<String, Object> one(String sql, Object... args) {
    Map<String, Object> row = optionalOne(sql, args);
    if (row == null) {
      throw new IllegalArgumentException("查询对象不存在");
    }
    return row;
  }

  public Map<String, Object> optionalOne(String sql, Object... args) {
    return mapper.selectOne(SqlCommand.of(sql, args));
  }

  public Object scalar(String sql, Object... args) {
    Map<String, Object> row = optionalOne(sql, args);
    return row == null || row.isEmpty() ? null : row.values().iterator().next();
  }

  public int update(String sql, Object... args) {
    return mapper.update(SqlCommand.of(sql, args));
  }

  public long insert(String sql, Object... args) {
    SqlCommand command = SqlCommand.of(sql, args);
    mapper.insert(command);
    if (command.getGeneratedId() == null) {
      throw new IllegalStateException("数据库未返回自增主键");
    }
    return command.getGeneratedId();
  }
}
