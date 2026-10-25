package com.forecast.app.market.card;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 挑战卡、开奖卡的分享凭证（forecast S14，表 market_share，只增不改）。SQL 在 {@code mapper/market/ShareMapper.xml}。 */
@Mapper
interface ShareMapper {

  /** 一行凭证（和它的市场对外编号） */
  final class Row {
    long id;
    String token;
    long marketId;
    long publicNo;
    long userId;
    String kind;
    long optionId;
    boolean showAmount;
    BigDecimal amount;
    LocalDateTime createdAt;
  }

  Row byToken(@Param("token") String token);

  Row find(@Param("userId") long userId, @Param("marketId") long marketId, @Param("kind") String kind, @Param("optionId") long optionId,
      @Param("showAmount") boolean showAmount);

  /** 已有同样的一行时什么都不做（唯一键 uk_market_share_once 兜底，约定 34：先普通读、再 INSERT IGNORE） */
  int insertIgnore(@Param("token") String token, @Param("marketId") long marketId, @Param("userId") long userId, @Param("kind") String kind,
      @Param("optionId") long optionId, @Param("showAmount") boolean showAmount, @Param("amount") BigDecimal amount, @Param("now") LocalDateTime now);
}
