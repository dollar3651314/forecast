package com.forecast.app.ledger.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 余额调整的日报（forecast S17）：报表里和平台收入、补偿支出分开单列，不相互冲减，也不和真实充值混在一起。
 * 按 UTC 日期，时间按入金 / 调整的记账时间。
 */
public interface AdjustReportApi {

  /**
   * 一天的余额调整。
   *
   * @param testCredit 测试入金合计（S17A，只在测试环境有）
   * @param testCredits 测试入金笔数
   * @param manualCredit 人工加款合计（S17B）
   * @param manualDebit 人工扣款合计（S17B，正数）
   */
  record Day(LocalDate day, BigDecimal testCredit, int testCredits, BigDecimal manualCredit, int manualCredits, BigDecimal manualDebit, int manualDebits) {

    public static Day empty(LocalDate day) {
      return new Day(day, BigDecimal.ZERO, 0, BigDecimal.ZERO, 0, BigDecimal.ZERO, 0);
    }
  }

  /** [from, to] 里有调整的日子，按日期升序 */
  List<Day> daily(LocalDate from, LocalDate to);
}
