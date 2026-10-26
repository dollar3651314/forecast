package com.forecast.app.growth.visit;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** growth_visit 的读写（forecast S13，MF-1），见 resources/mapper/growth/VisitMapper.xml。只有 growth 模块使用。 */
@Mapper
public interface VisitMapper {

  /** 按"日期 + 访问者 + 来源"去重：已经记过的不再记（INSERT IGNORE，唯一键 uk_growth_visit） */
  int insertIgnore(@Param("day") LocalDate day, @Param("visitor") String visitor, @Param("src") String src, @Param("cmp") String cmp,
      @Param("ref") String ref, @Param("start") String start, @Param("now") LocalDateTime now);

  /** 一天一个来源的访问人数 */
  class DayCount {
    public LocalDate day;
    public String src;
    public String cmp;
    public String ref;
    public long n;
  }

  List<DayCount> counts(@Param("from") LocalDate from, @Param("to") LocalDate to);
}
