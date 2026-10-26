package com.forecast.app.account.sharecode;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** account_share_code 的读写（forecast S15），见 resources/mapper/account/ShareCodeMapper.xml。只有 account 模块使用；只增不改。 */
@Mapper
interface ShareCodeMapper {

  class Row {
    long userId;
    String code;
  }

  String codeOf(@Param("userId") long userId);

  /** 已有这个客户或这个分享码时不插入（返回 0） */
  int insertIgnore(@Param("userId") long userId, @Param("code") String code, @Param("now") LocalDateTime now);

  List<Row> owners(@Param("codes") Collection<String> codes);
}
