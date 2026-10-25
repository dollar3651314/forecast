package com.forecast.app.resolve.auto;

import com.forecast.app.resolve.source.SourceGateway;
import java.util.ArrayList;
import java.util.List;

/**
 * 加密涨跌的判定（纯计算，需求第 4.3 节、E9、E10；技术方案第 6.2 节、X-7）：
 * <ul>
 *   <li>主源、备源都拿到：方向一致 → 这个方向；方向不同但有一边"正好相等"（E9 判涨导致的不同）→ 以首选（主源）为准；否则不一致。</li>
 *   <li>第三源（Coinbase，默认关闭）只在主备一方拿不到时补位：主源 + 第三源（仍按"正好相等以首选为准"）、或备源 + 第三源（没有首选，不一致就是不一致）。</li>
 *   <li>其他情况（拿不到、只有一个来源）：定不下来，下一轮重试。</li>
 * </ul>
 */
final class CryptoDecision {

  private CryptoDecision() {}

  /**
   * @param direction UP / DOWN；定不下来为空
   * @param basis 开始价、结束价按哪个来源显示（主源拿到时是主源）
   * @param used 参与判定的来源
   */
  record Verdict(String direction, String basis, List<String> used, String reason) {

    boolean decided() {
      return direction != null;
    }
  }

  static Verdict decide(SourceGateway.Result primary, SourceGateway.Result backup, SourceGateway.Result third) {
    SourceGateway.Result a = primary != null && primary.ok() ? primary : null;
    SourceGateway.Result b = backup != null && backup.ok() ? backup : null;
    SourceGateway.Result c = third != null && third.ok() ? third : null;
    if (a != null && b != null) return compare(a, b, true, "");
    if (a != null && c != null) return compare(a, c, true, "备源拿不到，第三源补位；");
    if (b != null && c != null) return compare(b, c, false, "主源拿不到，第三源补位；");
    List<String> missing = new ArrayList<>();
    for (SourceGateway.Result r : new SourceGateway.Result[] {primary, backup, third}) {
      if (r != null && !r.ok() && r.outcome() != SourceGateway.Outcome.DISABLED) missing.add(r.source() + " " + r.outcome().name().toLowerCase() + "（" + r.error() + "）");
    }
    return new Verdict(null, null, List.of(), "拿不到数据：" + String.join("；", missing));
  }

  private static Verdict compare(SourceGateway.Result x, SourceGateway.Result y, boolean xIsPrimary, String note) {
    String dx = x.candles().direction();
    String dy = y.candles().direction();
    List<String> used = List.of(x.source(), y.source());
    if (dx.equals(dy)) return new Verdict(dx, x.source(), used, note + "两个来源方向一致：" + dx);
    if (xIsPrimary && (x.candles().tie() || y.candles().tie())) {
      return new Verdict(dx, x.source(), used, note + "正好相等导致方向不同（" + x.source() + " " + dx + " / " + y.source() + " " + dy + "），以首选为准：" + dx);
    }
    return new Verdict(null, null, used, note + "两个来源方向不一致：" + x.source() + " " + dx + " / " + y.source() + " " + dy);
  }
}
