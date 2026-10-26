package com.forecast.app.market.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 给推广功能用的公开市场数据（forecast S16：MF-17 数据页的"我们今天的场次"、MF-7 机器人的 {@code /btc}、{@code /today} 和频道的开奖卡）。
 * 只有公开数据（和不登录看到的列表、详情、开奖记录同一份），<b>没有任何客户的个人信息</b>。market 模块实现（{@code market/core/PublicMarketsService}）。
 */
public interface PublicMarketsApi {

  /**
   * 一个选项的公开奖池数字。
   *
   * @param pct 概率（显示用，已经按需求第 3.4 节舍入，例如 "62.5%"、"&lt;1%"、"—"）
   * @param amount 选项奖池（2 位小数的字符串）
   */
  record OptionStat(String optionNo, I18nText label, int bettors, String amount, String pct) {}

  /**
   * 一个市场的公开数字。
   *
   * @param status 客户端的状态（listed / paused / closed / …）；upcoming：已上架但还没到开放下注时间
   * @param poolTotal 奖池合计（2 位小数）
   * @param bettors 下注人数（不重复）
   * @param feeRate 费率（百分比字符串，"5"）
   */
  record MarketStat(long publicNo, String category, I18nText title, String status, boolean upcoming, Instant openAt, Instant closeAt, Instant eventStartAt,
      String poolTotal, int bettors, String feeRate, List<OptionStat> options) {}

  /** 今日场次（UTC 的今天，带"列入今日场次"标记的市场）；没有时 next 是下一场（也可能没有） */
  record Slate(List<MarketStat> items, MarketStat next) {}

  Slate todaySlate();

  /**
   * 某个币对的加密自动生成市场里"当前这一场"：正在开放下注的（截止最早的那个）；没有时是下一场还没开放的；都没有为空。
   *
   * @param symbol 例如 {@code BTCUSDT}（自动生成模板的币对）
   */
  Optional<MarketStat> currentAutogen(String symbol);

  /** 一个已结束（已结算 / 已取消 / 已作废）的"今日场次"市场 */
  record Ended(long publicNo, String status, Instant endedAt) {}

  /** since 之后结束的、带"列入今日场次"标记的市场，按结束时间先后（频道开奖卡用：需求第 6.3 节"没有标记的市场一张都不发"） */
  List<Ended> endedSlateSince(Instant since, int limit);

  /** 最近结束的一个市场（不论有没有标记；模板审定页的预览用）；没有为空 */
  Optional<Long> latestEnded();

  /**
   * 自动发布用的一张卡片（MQ-2、MF-7）：和分享卡片（S14）同一个模板、同一份系统数据。
   *
   * @param png 1200 × 630 的 PNG
   * @param text 附带的文字（和图片同一份内容，一行一条）
   * @param title 市场题目
   */
  record PostCard(String kind, String lang, byte[] png, String text, String title) {}

  /** 开奖卡（已结算 / 已作废 / 已取消的市场；其他状态、不存在为空）。lang：en / zh-CN / zh-TW */
  Optional<PostCard> resultCard(long publicNo, String lang);

  /**
   * 一种卡片一种语言的模板文字的指纹（sha256，模板文件 {@code content/cards.json} 里这种卡片用到的全部文字）。审定模板时记下它；
   * 模板文字改了一个字指纹就变（需求第 6.3 节"已审定的模板改了任何一个字，就变回草稿"）。
   */
  String cardTemplateFingerprint(String kind, String lang);
}
