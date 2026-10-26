package com.forecast.app.content.page;

import com.forecast.app.resolve.api.MarketDataApi;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * BTC 每小时涨跌统计（forecast S15，MF-11 "BTC 每小时涨跌统计页"、MF-17 的"过去 N 小时 BTC 每小时涨跌的比例"，AC-96：要和同一段 K 线手算的结果一致）。
 * 口径和本站加密市场一样：一小时的收盘价 ≥ 开盘价算"涨"（正好相等也算涨，需求第 4.3 节 E9），否则"跌"。百分比保留 1 位小数（四舍五入）。
 *
 * @param bars 用了几根 1 小时 K 线（7 天 = 168）
 * @param up24 过去 24 小时里涨的小时数
 * @param change24Pct 过去 24 小时的涨跌幅（最后收盘价相对 24 小时前第一根的开盘价，2 位小数，带符号）
 * @param byHour 按 UTC 小时（0～23）：过去 7 天这个小时涨的天数
 */
public record BtcStats(String symbol, String source, String fetchedAt, String sha256, String from, String to, int bars, int up24, int down24, String up24Pct,
    int up7d, int down7d, String up7dPct, String lastClose, String change24Pct, List<HourRow> byHour) {

  /** 一个 UTC 小时：涨的天数、总天数、比例 */
  public record HourRow(int hour, int up, int total, String pct) {}

  public static BtcStats of(MarketDataApi.Hourly h) {
    List<MarketDataApi.Bar> b = h.bars();
    int n = b.size();
    List<MarketDataApi.Bar> last24 = b.subList(Math.max(0, n - 24), n);
    int up24 = (int) last24.stream().filter(BtcStats::up).count();
    int up7 = (int) b.stream().filter(BtcStats::up).count();
    int[] hu = new int[24];
    int[] ht = new int[24];
    for (MarketDataApi.Bar x : b) {
      int hr = x.openTime().atZone(ZoneOffset.UTC).getHour();
      ht[hr]++;
      if (up(x)) hu[hr]++;
    }
    List<HourRow> rows = new ArrayList<>();
    for (int i = 0; i < 24; i++) rows.add(new HourRow(i, hu[i], ht[i], pct(hu[i], ht[i])));
    BigDecimal lastClose = b.get(n - 1).close();
    BigDecimal open24 = last24.get(0).open();
    String change = lastClose.subtract(open24).multiply(BigDecimal.valueOf(100)).divide(open24, 2, RoundingMode.HALF_UP).toPlainString();
    if (!change.startsWith("-")) change = "+" + change;
    return new BtcStats(h.symbol(), h.source(), h.fetchedAt().toString(), h.sha256(), b.get(0).openTime().toString(),
        b.get(n - 1).openTime().plusSeconds(3600).toString(), n, up24, last24.size() - up24, pct(up24, last24.size()), up7, n - up7, pct(up7, n),
        lastClose.stripTrailingZeros().toPlainString(), change, rows);
  }

  static boolean up(MarketDataApi.Bar x) {
    return x.close().compareTo(x.open()) >= 0;
  }

  static String pct(int a, int b) {
    if (b == 0) return "—";
    return BigDecimal.valueOf(a).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(b), 1, RoundingMode.HALF_UP).toPlainString();
  }
}
