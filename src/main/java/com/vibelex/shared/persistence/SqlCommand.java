package com.vibelex.shared.persistence;

import java.util.Arrays;
import java.util.List;

/**
 * MyBatis 动态 SQL 命令。
 *
 * <p>项目中的 SQL 仍由各领域服务拥有，本对象只负责把 SQL 与位置参数安全地 交给 MyBatis。它不包含任何业务规则，也不会被领域模型引用。
 */
public final class SqlCommand {

  private final String sql;
  private final List<Object> args;
  private Long generatedId;

  private SqlCommand(String sql, Object[] args) {
    this.sql = sql;
    this.args = Arrays.asList(args);
  }

  public static SqlCommand of(String sql, Object... args) {
    return new SqlCommand(sql, args);
  }

  public String getSql() {
    return sql;
  }

  public List<Object> getArgs() {
    return args;
  }

  public Long getGeneratedId() {
    return generatedId;
  }

  public void setGeneratedId(Long generatedId) {
    this.generatedId = generatedId;
  }
}
