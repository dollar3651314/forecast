package com.forecast.app.ledger.adjust;

import com.forecast.app.ledger.api.AdjustReportApi;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link AdjustReportApi} 的实现：按天合计测试入金单（S17A）和人工调账单（S17B）。 */
@Service
class AdjustReports implements AdjustReportApi {

  private final AdjustMapper mapper;

  AdjustReports(AdjustMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  @Transactional(readOnly = true)
  public List<Day> daily(LocalDate from, LocalDate to) {
    Map<LocalDate, Day> out = new TreeMap<>();
    for (AdjustMapper.DayRow r : mapper.testCreditByDay(from.atStartOfDay(), to.plusDays(1).atStartOfDay())) {
      out.put(r.day, new Day(r.day, nz(r.amount), r.n, BigDecimal.ZERO, 0, BigDecimal.ZERO, 0));
    }
    for (AdjustMapper.AdjustDayRow r : mapper.adjustByDay(from.atStartOfDay(), to.plusDays(1).atStartOfDay())) {
      Day d = out.getOrDefault(r.day, Day.empty(r.day));
      out.put(r.day, new Day(r.day, d.testCredit(), d.testCredits(), nz(r.credit), r.credits, nz(r.debit), r.debits));
    }
    return new ArrayList<>(out.values());
  }

  private static BigDecimal nz(BigDecimal v) {
    return v == null ? BigDecimal.ZERO : v;
  }
}
