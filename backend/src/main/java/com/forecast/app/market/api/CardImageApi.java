package com.forecast.app.market.api;

import java.util.List;
import java.util.Optional;

/**
 * 卡片图片（forecast S15，MF-9 内容数据工具用）：市场卡（和 S14 的分享卡片同一张，数字全部来自系统）和内部工具的数据卡、对照图
 * （同一套画法：1200 × 630 PNG）。只给后台的内容工具用；工具出的图只能下载，不会自动发布。
 */
public interface CardImageApi {

  /** 一行：名字、概率（可以为空）、概率条长度 0～1（小于 0 不画条）、右边的数字 */
  record Row(String label, String pct, double ratio, String detail) {}

  /**
   * 一张卡片的内容（文字已经按语言写好，数字由调用方从数据里填）。
   *
   * @param lang en / zh-CN / zh-TW
   * @param chip 左上角的小标签
   * @param headline 大字的一句（可以为空）
   * @param stamp 数据来源和时间
   */
  record Spec(String lang, String chip, String status, String headline, String title, List<Row> rows, List<String> facts, List<String> notes, String stamp) {}

  /** 图片和同一份内容的文字 */
  record Rendered(byte[] png, String text) {}

  /** 市场卡（已结束的是开奖卡）；草稿、不存在的为空 */
  Optional<Rendered> marketCard(long publicNo, String lang);

  /** 按内容画一张 */
  Rendered render(Spec spec);
}
