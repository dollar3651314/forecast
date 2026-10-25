package com.forecast.app.settle.view;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 客户端要的结算信息（forecast S11，只读）。SQL 在 {@code mapper/settle/SettleViewMapper.xml}。 */
@Mapper
interface SettleViewMapper {

  /** 一张结算单 / 退款单的公开数字 */
  final class RunRow {
    long id;
    String kind;
    String status;
    BigDecimal feeRate;
    BigDecimal poolTotal;
    BigDecimal sumFee;
    BigDecimal dust;
    BigDecimal sumPayout;
    int winnerPositions;
    int loserPositions;
    LocalDateTime doneAt;
  }

  /** 本人在一个市场一个选项上的结算明细（派彩已完成的结算单） */
  final class ItemRow {
    long marketId;
    long optionId;
    boolean win;
    BigDecimal stake;
    BigDecimal fee;
    BigDecimal payout;
  }

  /** 合计 */
  final class SumRow {
    BigDecimal stake;
    BigDecimal payout;
    BigDecimal fee;
  }

  RunRow run(@Param("marketId") long marketId, @Param("kind") String kind);

  /** 本人在这些市场的结算明细；只取 PAYOUT 且 DONE 的结算单 */
  List<ItemRow> userItems(@Param("userId") long userId, @Param("marketIds") Collection<Long> marketIds);

  /** 本人全部 DONE 结算单的本金、派彩、抽成合计 */
  SumRow userSettled(@Param("userId") long userId);

  /** 本人收到的补偿合计；marketId 为空时是全部市场 */
  BigDecimal userCompensation(@Param("userId") long userId, @Param("marketId") Long marketId);

  /** 本人在一个市场已经退回的金额（取消、作废的退款单，E14、E15、提前截止的单笔退回） */
  BigDecimal userRefunded(@Param("userId") long userId, @Param("marketId") long marketId);
}
