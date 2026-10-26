package com.forecast.app.account.device;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** account_device 的读写（forecast S10），见 resources/mapper/account/DeviceMapper.xml。只有 account 模块使用。 */
@Mapper
interface DeviceMapper {

  class Row {
    long id;
    long userId;
    String kind;
    String deviceHash;
    String ip;
    LocalDateTime createdAt;
  }

  int insert(@Param("userId") long userId, @Param("kind") String kind, @Param("deviceHash") String deviceHash, @Param("ip") String ip,
      @Param("at") LocalDateTime at);

  List<Row> after(@Param("afterId") long afterId, @Param("limit") int limit);
}
