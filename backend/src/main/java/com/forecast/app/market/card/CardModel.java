package com.forecast.app.market.card;

import java.util.ArrayList;
import java.util.List;

/**
 * 一张分享卡片的内容（forecast S14，MF-5）：图片（{@link CardPainter}）和分享文字（{@link #text()}）都从这一份来，所以两者的数字一定一样。
 * 字段里的文字已经按语言套好模板（{@code resources/content/cards.json}），数字全部取自系统（奖池、人数、概率、预计赔率、结算合计），
 * <b>没有任何客户的个人信息</b>（开奖卡的"我的派彩"只有客户自己选择显示时才有，见 {@link CardService}）。
 *
 * @param kind market / result / challenge / win / countdown / weekly
 * @param lang en / zh-CN / zh-TW
 * @param brand 品牌名
 * @param chip 左上角的品类（"加密 1 小时"），周报是"周报"
 * @param status 右上角的状态（"可下注""已结算"）
 * @param headline 大字的一句（挑战卡"我押了「涨」，谁敢押「跌」？"、倒计时卡"距截止 5 分钟"、开奖卡"结果：涨"）；没有时为空
 * @param title 市场题目 / 周报标题
 * @param rows 选项行（或周报的数字行）
 * @param facts 关键数字（奖池、人数、截止时间、派彩合计……），一行一条
 * @param notes 规则说明（"以截止时为准""只有一边有人押时全额退款"），小字
 * @param stamp 数据时间（"数据时间 2026-10-25 12:55 UTC"）
 * @param highlight 大字那一句的语气：UP / DOWN / NEUTRAL（开奖卡押中 → UP；作废 → NEUTRAL）
 */
public record CardModel(String kind, String lang, String brand, String chip, String status, String headline, String title, List<Row> rows, List<String> facts,
    List<String> notes, String stamp, Tone highlight) {

  /** 颜色的语气：只有加密"涨 / 跌"和押中用绿红，其他用中性色和主色（PQ-1、约定 46） */
  public enum Tone {
    UP, DOWN, PRIMARY, NEUTRAL
  }

  /**
   * 一行。
   *
   * @param label 选项名 / 数字名
   * @param pct 概率（"58.3%"）；周报的数字行为空
   * @param ratio 概率条的长度 0～1；小于 0 表示不画条（周报）
   * @param detail 右边的数字（"12 人 · 680.00 USDT · ×1.73"）
   * @param tone 条和概率的颜色
   * @param mark 这一行是结果 / 我押的（画 ✓ 并加粗）
   */
  public record Row(String label, String pct, double ratio, String detail, Tone tone, boolean mark) {}

  /** 分享时附带的文字（系统分享面板、复制、以后的自动发布）：和图片同一份内容，一行一条 */
  public String text() {
    List<String> lines = new ArrayList<>();
    if (headline != null && !headline.isBlank()) lines.add(headline);
    lines.add(title);
    for (Row r : rows) {
      StringBuilder sb = new StringBuilder(r.mark() ? "✓ " : "· ").append(r.label());
      if (r.pct() != null && !r.pct().isBlank()) sb.append(' ').append(r.pct());
      if (r.detail() != null && !r.detail().isBlank()) sb.append(" · ").append(r.detail());
      lines.add(sb.toString());
    }
    lines.addAll(facts);
    lines.addAll(notes);
    return String.join("\n", lines);
  }

  /** 缓存用的指纹：内容一样就是同一张图（数据时间只精确到分钟，同一分钟里重复请求不重画） */
  String fingerprint() {
    return kind + '\u0001' + lang + '\u0001' + brand + '\u0001' + chip + '\u0001' + status + '\u0001' + headline + '\u0001' + title + '\u0001' + rows + '\u0001'
        + facts + '\u0001' + notes + '\u0001' + stamp + '\u0001' + highlight;
  }
}
