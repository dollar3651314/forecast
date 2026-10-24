package com.forecast.app.common.config;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
interface ConfigMapper {

  /** common_config 的一行 */
  class Row {
    String configKey;
    String value;
    Long updatedBy;
    LocalDateTime updatedAt;
  }

  List<Row> all();

  Row lock(@Param("key") String key);

  void upsert(@Param("key") String key, @Param("value") String value, @Param("adminId") long adminId, @Param("now") LocalDateTime now);
}
