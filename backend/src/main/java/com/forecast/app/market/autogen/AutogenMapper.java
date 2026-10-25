package com.forecast.app.market.autogen;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 自动生成模板、规则文本模板的 SQL（resources/mapper/market/AutogenMapper.xml，forecast S6）。只有 market 模块能用。
 * 修改自动生成模板的语句都带 {@code enabled = 0}（只能在关闭时改）；启用、关闭是带旧值的条件更新。
 */
@Mapper
interface AutogenMapper {

  List<AutogenRows.Autogen> list();

  List<AutogenRows.Autogen> enabled();

  AutogenRows.Autogen byPublicNo(@Param("publicNo") long publicNo);

  AutogenRows.Autogen bySymbolPeriod(@Param("symbol") String symbol, @Param("period") String period);

  /** 排他锁（启用、关闭、修改） */
  AutogenRows.Autogen lockByPublicNo(@Param("publicNo") long publicNo);

  /** 共享锁（生成任务每个窗口的事务里：和启用、关闭串行，关闭提交以后不会再生成） */
  AutogenRows.Autogen lockForShare(@Param("id") long id);

  int insert(AutogenRows.Autogen a);

  /** 只在关闭时改；清掉检查记录（改了以后要重新检查才能启用） */
  int update(AutogenRows.Autogen a);

  int enable(@Param("id") long id, @Param("checkedBy") long checkedBy, @Param("templateVersion") int templateVersion, @Param("now") LocalDateTime now);

  int disable(@Param("id") long id, @Param("by") long by, @Param("now") LocalDateTime now);

  List<AutogenRows.Template> templates();

  AutogenRows.Template template(@Param("id") long id);

  AutogenRows.Template templateByPublicNo(@Param("publicNo") long publicNo);

  /** 排他锁（修改规则文本模板时，和启用串行） */
  AutogenRows.Template lockTemplate(@Param("id") long id);

  int updateTemplate(AutogenRows.Template t);
}
