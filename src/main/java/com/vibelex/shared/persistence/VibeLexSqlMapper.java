package com.vibelex.shared.persistence;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.InsertProvider;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.SelectProvider;
import org.apache.ibatis.annotations.UpdateProvider;

/**
 * VibeLex 的 MyBatis SQL 执行入口。
 *
 * <p>领域服务通过 {@link MyBatisDatabase} 使用本 Mapper，避免直接依赖 SqlSession，同时保留各领域对自身 SQL 的所有权。
 */
@Mapper
public interface VibeLexSqlMapper {

  @SelectProvider(type = SqlCommandProvider.class, method = "sql")
  List<Map<String, Object>> selectList(SqlCommand command);

  @SelectProvider(type = SqlCommandProvider.class, method = "sql")
  Map<String, Object> selectOne(SqlCommand command);

  @UpdateProvider(type = SqlCommandProvider.class, method = "sql")
  int update(SqlCommand command);

  @InsertProvider(type = SqlCommandProvider.class, method = "sql")
  @Options(useGeneratedKeys = true, keyProperty = "generatedId", keyColumn = "id")
  int insert(SqlCommand command);
}
