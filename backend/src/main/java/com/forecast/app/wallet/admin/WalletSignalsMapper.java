package com.forecast.app.wallet.admin;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 风控要用的钱包数据（forecast S10），见 resources/mapper/wallet/WalletSignalsMapper.xml。只有 wallet 模块使用。 */
@Mapper
interface WalletSignalsMapper {

  class Row {
    long id;
    long publicNo;
    long userId;
    String chain;
    String address;
    BigDecimal amount;
    LocalDateTime at;
    boolean ripe;
  }

  /** 充值的写入时间是数据库时间：ripe = 早于"数据库现在 − lagMillis" */
  List<Row> depositsAfter(@Param("afterId") long afterId, @Param("lagMillis") long lagMillis, @Param("limit") int limit);

  List<Row> whitelistAfter(@Param("afterId") long afterId, @Param("limit") int limit);

  List<Row> withdrawalsAfter(@Param("afterId") long afterId, @Param("limit") int limit);

  /** S13：客户第一笔已入账充值的时间 */
  class FirstCredit {
    long userId;
    java.time.LocalDateTime at;
  }

  List<FirstCredit> firstCredited(@Param("userIds") java.util.Collection<Long> userIds);
}
