package com.forecast.app.settle.run;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** settle 模块各表的一行（MyBatis 按字段名映射，列名下划线转驼峰；插入时按 getter 取值）。时间都是 UTC。 */
public final class SettleRows {

  private SettleRows() {}

  /** settle_run */
  public static class Run {
    public Long id;
    public long publicNo;
    public long marketId;
    public String kind;
    public String reason;
    public Long winningOptionId;
    public BigDecimal feeRate;
    public BigDecimal poolTotal;
    public BigDecimal winnerPool;
    public BigDecimal loserPool;
    public BigDecimal sumGross;
    public BigDecimal sumFee;
    public BigDecimal dust;
    public BigDecimal sumPayout;
    public int winnerPositions;
    public int loserPositions;
    public int itemCount;
    public boolean conservationOk;
    public String status;
    public String abnormalReason;
    public int batchSize;
    public String resolvedBy;
    public LocalDateTime calculatedAt;
    public LocalDateTime doneAt;
    public int version;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public long getPublicNo() { return publicNo; }
    public long getMarketId() { return marketId; }
    public String getKind() { return kind; }
    public String getReason() { return reason; }
    public Long getWinningOptionId() { return winningOptionId; }
    public BigDecimal getFeeRate() { return feeRate; }
    public BigDecimal getPoolTotal() { return poolTotal; }
    public BigDecimal getWinnerPool() { return winnerPool; }
    public BigDecimal getLoserPool() { return loserPool; }
    public BigDecimal getSumGross() { return sumGross; }
    public BigDecimal getSumFee() { return sumFee; }
    public BigDecimal getDust() { return dust; }
    public BigDecimal getSumPayout() { return sumPayout; }
    public int getWinnerPositions() { return winnerPositions; }
    public int getLoserPositions() { return loserPositions; }
    public int getItemCount() { return itemCount; }
    public boolean isConservationOk() { return conservationOk; }
    public String getStatus() { return status; }
    public String getAbnormalReason() { return abnormalReason; }
    public int getBatchSize() { return batchSize; }
    public String getResolvedBy() { return resolvedBy; }
    public LocalDateTime getCalculatedAt() { return calculatedAt; }
  }

  /** settle_item：结算明细（每个持仓一行） */
  public static class Item {
    public Long id;
    public long runId;
    public long marketId;
    public long positionId;
    public long userId;
    public long optionId;
    public boolean win;
    public BigDecimal stake;
    public BigDecimal gross;
    public BigDecimal fee;
    public BigDecimal payout;
    public String status;
    public LocalDateTime doneAt;

    public long getRunId() { return runId; }
    public long getMarketId() { return marketId; }
    public long getPositionId() { return positionId; }
    public long getUserId() { return userId; }
    public long getOptionId() { return optionId; }
    public boolean isWin() { return win; }
    public BigDecimal getStake() { return stake; }
    public BigDecimal getGross() { return gross; }
    public BigDecimal getFee() { return fee; }
    public BigDecimal getPayout() { return payout; }
  }

  /** settle_refund_item：单笔退款 */
  public static class Refund {
    public Long id;
    public Long runId;
    public long marketId;
    public long betId;
    public long userId;
    public BigDecimal amount;
    public String reason;
    public String status;
    public LocalDateTime createdAt;
    public LocalDateTime doneAt;
    /** 只在读的时候带上（下注编号，后台明细用） */
    public Long betNo;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getRunId() { return runId; }
    public long getMarketId() { return marketId; }
    public long getBetId() { return betId; }
    public long getUserId() { return userId; }
    public BigDecimal getAmount() { return amount; }
    public String getReason() { return reason; }
    public String getStatus() { return status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getDoneAt() { return doneAt; }
  }

  /** settle_pool_recon_issue */
  public static class Issue {
    public Long id;
    public String refKey;
    public Long marketId;
    public LocalDate checkDate;
    public String kind;
    public String source;
    public BigDecimal expected;
    public BigDecimal actual;
    public BigDecimal diff;
    public String detail;
    public String status;
    public Long resolvedBy;
    public LocalDateTime resolvedAt;
    public String note;
    public LocalDateTime createdAt;

    public String getRefKey() { return refKey; }
    public Long getMarketId() { return marketId; }
    public LocalDate getCheckDate() { return checkDate; }
    public String getKind() { return kind; }
    public String getSource() { return source; }
    public BigDecimal getExpected() { return expected; }
    public BigDecimal getActual() { return actual; }
    public BigDecimal getDiff() { return diff; }
    public String getDetail() { return detail; }
    public LocalDateTime getCreatedAt() { return createdAt; }
  }
}
