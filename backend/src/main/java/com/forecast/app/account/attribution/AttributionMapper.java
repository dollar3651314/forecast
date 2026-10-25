package com.forecast.app.account.attribution;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** account_attribution 的读写（forecast S13，MF-1），见 resources/mapper/account/AttributionMapper.xml。只有 account 模块使用。 */
@Mapper
interface AttributionMapper {

  class Row {
    long userId;
    String firstSrc;
    String firstCmp;
    String firstRef;
    String firstStart;
    LocalDateTime firstAt;
    String regSrc;
    String regCmp;
    String regRef;
    String regStart;
    LocalDateTime regSeenAt;
    LocalDateTime createdAt;
    /** 只有 registrations 填：account_user.created_at */
    LocalDateTime registeredAt;
  }

  int insert(Row r);

  Row find(@Param("userId") long userId);

  /** 注册时间在 [from, to) 的客户（LEFT JOIN 来源，没来源的来源列为空），按 id */
  List<Row> registrations(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to, @Param("limit") int limit);
}
