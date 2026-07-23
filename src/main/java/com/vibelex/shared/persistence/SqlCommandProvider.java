package com.vibelex.shared.persistence;

/**
 * 将 JDBC 风格的问号占位符转换为 MyBatis 参数表达式。
 *
 * <p>SQL 均来自项目源码，不接受外部传入的 SQL 文本；请求数据只会作为参数值 绑定，从而避免把管理端输入直接拼接进 SQL。
 */
public final class SqlCommandProvider {

  private SqlCommandProvider() {}

  public static String sql(SqlCommand command) {
    String source = command.getSql();
    StringBuilder mapped = new StringBuilder(source.length() + 32);
    int parameterIndex = 0;
    boolean quoted = false;

    for (int index = 0; index < source.length(); index++) {
      char current = source.charAt(index);

      if (current == '\'') {
        quoted = !quoted;
        mapped.append(current);
      } else if (current == '?' && !quoted) {
        mapped.append("#{args[").append(parameterIndex++).append("]}");
      } else {
        mapped.append(current);
      }
    }

    if (parameterIndex != command.getArgs().size()) {
      throw new IllegalArgumentException("SQL 占位符数量与参数数量不一致");
    }
    return mapped.toString();
  }
}
