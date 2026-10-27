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

/** {@link AdjustReportApi} 的实现：按天合计测试入金单（S17A）。 */
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
      out.put(r.day, new Day(r.day, r.amount == null ? BigDecimal.ZERO : r.amount, r.n));
    }
    return new ArrayList<>(out.values());
  }
}
