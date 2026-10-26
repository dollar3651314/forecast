package com.forecast.app.content.draft;

import com.forecast.app.common.config.ConfigKey;
import com.forecast.app.common.config.SystemConfig;
import com.forecast.app.content.page.BtcStats;
import com.forecast.app.market.api.MarketCategory;
import com.forecast.app.market.api.MarketPublicApi;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 拼"引用的数据"（forecast S15，自动化需求第 4.3 节第 1 条、第 5 节）：<b>只有两种数据</b>——系统数据（市场、奖池汇总、平台规则）和标明来源的公开数据
 * （币安 K 线；Polymarket 默认关闭）。全部来自公开快照（{@link MarketPublicApi}，和不登录的客户看到的一样），<b>没有任何客户的个人数据</b>（NF-16）。
 * 发给 Gemini 的提示只用这里的内容拼（{@link Prompts}），文章里的数字也只按这里核对。
 */
@Component
public class DataRefs {

  static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'", Locale.ROOT).withZone(ZoneOffset.UTC);

  private final SystemConfig config;

  DataRefs(SystemConfig config) {
    this.config = config;
  }

  static String time(Instant t) {
    return t == null ? "—" : TIME.format(t);
  }

  static String category(MarketCategory c, boolean en) {
    if (c == null) return "";
    return switch (c) {
      case FOOTBALL -> en ? "Football" : "足球";
      case NBA -> "NBA";
      case CRYPTO_15M -> en ? "Crypto 15-minute" : "加密 15 分钟";
      case CRYPTO_1H -> en ? "Crypto 1-hour" : "加密 1 小时";
      case MACRO -> en ? "Economy" : "经济";
    };
  }

  /** 一个市场的公开数据：题目、品类、时间、奖池、人数、各选项的奖池 / 人数 / 概率 / 预计赔率、抽成比例 */
  public List<Ref> market(MarketPublicApi.Snapshot s) {
    String src = "本站奖池 / This site's pool";
    String at = s.at().toString();
    List<Ref> r = new ArrayList<>();
    r.add(new Ref("market.title.zh", "题目（中文）", "Question (Chinese)", s.title().zhCN(), src, at));
    r.add(new Ref("market.title.en", "题目（英文）", "Question (English)", s.title().en(), src, at));
    r.add(new Ref("market.category", "品类", "Category", category(s.category(), false) + " / " + category(s.category(), true), src, at));
    r.add(new Ref("market.closeAt", "截止下注", "Betting closes", time(s.closeAt()), src, at));
    r.add(new Ref("market.eventStartAt", "事件开始", "Event starts", time(s.eventStartAt()), src, at));
    r.add(new Ref("market.total", "奖池合计（USDT）", "Total pool (USDT)", s.total(), src, at));
    r.add(new Ref("market.bettors", "下注人数", "Number of bettors", String.valueOf(s.bettors()), src, at));
    r.add(new Ref("market.fee", "平台抽成（赢家盈利的百分比）", "Platform fee (% of winners' profit)", s.feeRate() + "%", src, at));
    for (MarketPublicApi.Option o : s.options()) {
      String k = "option." + o.seq();
      String name = o.label().zhCN() + " / " + o.label().en();
      r.add(new Ref(k + ".amount", "选项「" + name + "」的奖池（USDT）", "Pool on \"" + o.label().en() + "\" (USDT)", o.amount(), src, at));
      r.add(new Ref(k + ".bettors", "选项「" + name + "」的人数", "Bettors on \"" + o.label().en() + "\"", String.valueOf(o.bettors()), src, at));
      r.add(new Ref(k + ".pct", "选项「" + name + "」的概率（按奖池比例）", "Share of the pool on \"" + o.label().en() + "\"", pct(o.pct()), src, at));
      if (o.mult() != null) {
        r.add(new Ref(k + ".mult", "选项「" + name + "」当前预计赔率（已扣抽成，最终以截止时为准）",
            "Current estimated payout multiple on \"" + o.label().en() + "\" (after fee; final at close)", "×" + o.mult(), src, at));
      }
    }
    return r;
  }

  private static String pct(String p) {
    if (p == null || p.equals("—")) return "—";
    return p.endsWith("%") ? p : p + "%";
  }

  /** BTC 每小时涨跌统计（来源写明币安 / OKX 和查询时间） */
  public List<Ref> btc(BtcStats s) {
    String src = s.source() + " 1 小时 K 线 / " + s.source() + " 1h candles";
    String at = s.fetchedAt();
    List<Ref> r = new ArrayList<>();
    r.add(new Ref("btc.window", "统计区间（UTC）", "Period (UTC)", time(Instant.parse(s.from())) + " — " + time(Instant.parse(s.to())), src, at));
    r.add(new Ref("btc.hours24", "过去 24 小时的小时数", "Hours in the last 24 hours", "24", src, at));
    r.add(new Ref("btc.up24", "过去 24 小时里上涨的小时数", "Up hours in the last 24 hours", String.valueOf(s.up24()), src, at));
    r.add(new Ref("btc.down24", "过去 24 小时里下跌的小时数", "Down hours in the last 24 hours", String.valueOf(s.down24()), src, at));
    r.add(new Ref("btc.up24Pct", "过去 24 小时上涨小时的比例", "Share of up hours in the last 24 hours", s.up24Pct() + "%", src, at));
    r.add(new Ref("btc.days", "统计天数", "Days covered", "7", src, at));
    r.add(new Ref("btc.hours7d", "过去 7 天的小时数", "Hours in the last 7 days", String.valueOf(s.bars()), src, at));
    r.add(new Ref("btc.up7d", "过去 7 天里上涨的小时数", "Up hours in the last 7 days", String.valueOf(s.up7d()), src, at));
    r.add(new Ref("btc.down7d", "过去 7 天里下跌的小时数", "Down hours in the last 7 days", String.valueOf(s.down7d()), src, at));
    r.add(new Ref("btc.up7dPct", "过去 7 天上涨小时的比例", "Share of up hours in the last 7 days", s.up7dPct() + "%", src, at));
    r.add(new Ref("btc.lastClose", "最近一小时的收盘价（USDT）", "Last hourly close (USDT)", s.lastClose(), src, at));
    r.add(new Ref("btc.change24", "过去 24 小时涨跌幅", "Change over the last 24 hours", s.change24Pct() + "%", src, at));
    return r;
  }

  /** 平台规则（教程、对比文章用）：抽成、最小提现、充值网络、不能撤单、只有一边有人押全额退款、平台不当对手方 */
  public List<Ref> rules(Instant at) {
    String src = "平台规则·说明页 / Platform rules · how-to page";
    String a = at.toString();
    Set<String> fees = new LinkedHashSet<>();
    for (MarketCategory c : MarketCategory.values()) fees.add(strip(config.decimal(c.feeKey, BigDecimal.valueOf(5))) + "%");
    List<Ref> r = new ArrayList<>();
    r.add(new Ref("rules.fee", "平台抽成：赢家盈利的百分比（各品类默认值）", "Platform fee: % of winners' profit (default by category)", String.join(" / ", fees), src, a));
    r.add(new Ref("rules.minWithdraw", "单笔最小提现（USDT）", "Minimum withdrawal (USDT)", strip(config.decimal(ConfigKey.WITHDRAW_MIN_AMOUNT, BigDecimal.valueOf(100))),
        src, a));
    r.add(new Ref("rules.networks", "充值网络", "Deposit networks", "ERC20、BEP20、TRC20（USDT） / ERC20, BEP20, TRC20 (USDT)", src, a));
    r.add(new Ref("rules.noCancel", "下注后能不能撤销", "Can a bet be cancelled", "不能 / No", src, a));
    r.add(new Ref("rules.oneSide", "截止时只有一边有人押", "Only one side has bets at close", "全额退款，不收抽成 / Full refund, no fee", src, a));
    r.add(new Ref("rules.noHouse", "平台是不是对手方", "Is the platform the counterparty", "不是：彩池玩法，输方奖池按比例分给赢方 / No: pari-mutuel pools", src, a));
    return r;
  }

  /** Amos 在工具里输入的观点摘要（回复草稿用；它里面的数字也算"引用的数据"） */
  public List<Ref> viewpoint(String text, Instant at) {
    return List.of(new Ref("viewpoint", "要回应的观点（Amos 输入的摘要）", "The viewpoint to respond to (summary entered by the operator)", text, "Amos 输入 / Entered by the operator", at.toString()));
  }

  private static String strip(BigDecimal v) {
    return v.stripTrailingZeros().toPlainString();
  }
}
