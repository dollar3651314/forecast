package com.forecast.app.content.tool;

import com.forecast.app.common.error.BizException;
import com.forecast.app.common.error.ValidationException;
import com.forecast.app.content.api.ContentAdminApi;
import com.forecast.app.content.check.Hant;
import com.forecast.app.content.check.Words;
import com.forecast.app.content.core.ContentLog;
import com.forecast.app.content.draft.DataRefs;
import com.forecast.app.content.draft.DraftService;
import com.forecast.app.content.draft.Ref;
import com.forecast.app.content.page.BtcStats;
import com.forecast.app.content.page.PageService;
import com.forecast.app.market.api.CardImageApi;
import com.forecast.app.market.api.MarketPublicApi;
import com.forecast.app.resolve.api.MarketDataApi;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

/**
 * 内容数据工具（forecast S15，MF-9；自动化需求第 4.1 节 #2 数据卡、#3 大 V 对照图）：图片按模板画（数字取数据管道，不用 AI，EX-2），
 * 印数据来源和查询时间；<b>只能下载、复制，没有发布</b>（对照图、回复由 Amos 手动发，AC-110、AC-113）。
 * <ul>
 *   <li>数据卡：一个市场的奖池（就是 S14 的市场卡）/ BTC 每小时涨跌统计。</li>
 *   <li>大 V 对照图：Amos 输入观点摘要 + 选一个市场；图上是我们池子的真实人数和比例；Polymarket 同类题（公开接口，<b>默认关闭</b>，条款待核实）打开时附上，
 *       印"数据来自 Polymarket 公开接口，非 Polymarket 官方"和查询时间。有 Gemini 时同时起草中英文回复（只能复制）。</li>
 * </ul>
 */
@Service
public class ToolService {

  static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'", Locale.ROOT).withZone(ZoneOffset.UTC);

  private final CardImageApi cards;
  private final MarketPublicApi markets;
  private final MarketDataApi data;
  private final PageService pages;
  private final DataRefs dataRefs;
  private final DraftService drafts;
  private final Words words;
  private final ContentLog contentLog;
  private final Clock clock;

  ToolService(CardImageApi cards, MarketPublicApi markets, MarketDataApi data, PageService pages, DataRefs dataRefs, DraftService drafts, Words words,
      ContentLog contentLog, Clock clock) {
    this.cards = cards;
    this.markets = markets;
    this.data = data;
    this.pages = pages;
    this.dataRefs = dataRefs;
    this.drafts = drafts;
    this.words = words;
    this.contentLog = contentLog;
    this.clock = clock;
  }

  public ContentAdminApi.ToolResult dataCard(String kind, String marketNo, String lang) {
    String l = lang(lang);
    if ("btc".equals(kind)) {
      BtcStats s = pages.ensureBtc(Duration.ofHours(2)).orElseThrow(() -> new BizException("DATA_UNAVAILABLE", "BTC 行情数据暂时取不到（币安、OKX 都不可用）"));
      CardImageApi.Rendered r = cards.render(btcSpec(s, l));
      contentLog.write("TOOL", "DATA_CARD", "btc", l, "OK", "BTC 统计数据卡", 0, null);
      return new ContentAdminApi.ToolResult(dataUrl(r.png()), r.text(), views(dataRefs.btc(s)), List.of(), List.of());
    }
    if (!"market".equals(kind)) throw new ValidationException(List.of(new ValidationException.Field("kind", "invalid")));
    MarketPublicApi.Snapshot s = market(marketNo);
    CardImageApi.Rendered r = cards.marketCard(s.publicNo(), l).orElseThrow(() -> new BizException("NOT_FOUND", "市场不存在或还是草稿"));
    contentLog.write("TOOL", "DATA_CARD", String.valueOf(s.publicNo()), l, "OK", "市场数据卡", 0, null);
    return new ContentAdminApi.ToolResult(dataUrl(r.png()), r.text(), views(dataRefs.market(s)), List.of(), List.of());
  }

  public ContentAdminApi.ToolResult compare(String marketNo, String viewpoint, String polymarketSlug, String lang, long adminId) {
    String l = lang(lang);
    String vp = viewpoint == null ? "" : viewpoint.strip();
    if (vp.length() < 4 || vp.length() > 200) throw new ValidationException(List.of(new ValidationException.Field("viewpoint", "length")));
    List<String> bad = Words.find(vp, words.list(Words.SENSITIVE));
    if (!bad.isEmpty()) throw new BizException("CONTENT_BANNED_WORDS", "观点涉及不能写的内容：" + String.join("、", bad));
    MarketPublicApi.Snapshot s = market(marketNo);
    Instant now = clock.instant();
    List<Ref> refs = new ArrayList<>(dataRefs.market(s));
    refs.addAll(dataRefs.viewpoint(vp, now));
    List<String> notes = new ArrayList<>();
    MarketDataApi.PolymarketQuote pm = null;
    if (polymarketSlug != null && !polymarketSlug.isBlank()) {
      pm = data.polymarket(polymarketSlug.strip());
      if (!pm.enabled()) notes.add("Polymarket 公开接口没有打开（使用条款待核实，默认关闭），图上只有本站数据");
      else if (!pm.ok()) notes.add("Polymarket 数据取不到：" + pm.error() + "；图上只有本站数据");
      else {
        String at = pm.fetchedAt().toString();
        for (MarketDataApi.Outcome o : pm.outcomes()) {
          refs.add(new Ref("polymarket." + o.name(), "Polymarket「" + pm.question() + "」选项 " + o.name() + " 的价格（隐含概率，数据来自 Polymarket 公开接口，非 Polymarket 官方）",
              "Polymarket \"" + pm.question() + "\" price of " + o.name() + " (implied probability; Polymarket public API, not affiliated with Polymarket)",
              pct(o.price()), "Polymarket 公开接口（非官方）", at));
        }
      }
    }
    CardImageApi.Rendered r = cards.render(compareSpec(s, vp, pm != null && pm.ok() ? pm : null, l, now));
    List<ContentAdminApi.Draft> replies = List.of();
    var no = drafts.aiUnavailable();
    if (no.isPresent()) notes.add("没有起草回复：" + no.get());
    else replies = drafts.generateReply(s.publicNo(), vp, refs, adminId).stream().map(drafts::view).toList();
    contentLog.write("TOOL", "COMPARE", String.valueOf(s.publicNo()), l, "OK", "对照图" + (replies.isEmpty() ? "" : "和回复草稿"), 0, adminId);
    return new ContentAdminApi.ToolResult(dataUrl(r.png()), r.text(), views(refs), replies, notes);
  }

  // ---------- 画什么 ----------

  CardImageApi.Spec btcSpec(BtcStats s, String l) {
    boolean en = "en".equals(l);
    List<CardImageApi.Row> rows = List.of(
        new CardImageApi.Row(en ? "Last 24 hours: up hours" : "过去 24 小时上涨的小时", s.up24Pct() + "%", ratio(s.up24Pct()),
            en ? s.up24() + " up · " + s.down24() + " down" : s.up24() + " 涨 · " + s.down24() + " 跌"),
        new CardImageApi.Row(en ? "Last 7 days: up hours" : "过去 7 天上涨的小时", s.up7dPct() + "%", ratio(s.up7dPct()),
            en ? s.up7d() + " up · " + s.down7d() + " down" : s.up7d() + " 涨 · " + s.down7d() + " 跌"));
    List<String> facts = List.of((en ? "Last hourly close: " : "最近一小时收盘价：") + s.lastClose() + " USDT · " + (en ? "24h change: " : "24 小时涨跌幅：")
        + s.change24Pct() + "%");
    List<String> notes = List.of(en ? "An hour counts as up when close ≥ open (same rule as our crypto markets)." : "收盘价 ≥ 开盘价算上涨（和本站加密市场同一口径）。");
    String stamp = (en ? "Data: " : "数据：") + s.source() + (en ? " 1h candles, fetched " : " 1 小时 K 线，查询时间 ") + TIME.format(Instant.parse(s.fetchedAt()));
    return new CardImageApi.Spec(l, en ? "Data card" : zh(l, "数据卡"), "BTC", null, en ? "BTC hourly up/down" : zh(l, "BTC 每小时涨跌统计"), rows, facts,
        tw(l, notes), tw(l, stamp));
  }

  CardImageApi.Spec compareSpec(MarketPublicApi.Snapshot s, String viewpoint, MarketDataApi.PolymarketQuote pm, String l, Instant now) {
    boolean en = "en".equals(l);
    List<CardImageApi.Row> rows = new ArrayList<>();
    for (MarketPublicApi.Option o : s.options()) {
      String label = en ? o.label().en() : "zh-TW".equals(l) ? o.label().zhTW() : o.label().zhCN();
      String pct = "—".equals(o.pct()) ? "—" : o.pct().endsWith("%") ? o.pct() : o.pct() + "%";
      rows.add(new CardImageApi.Row(label, pct, ratio(o.pct()), (en ? o.bettors() + " bettors · " : o.bettors() + " 人 · ") + o.amount() + " USDT"));
    }
    List<String> facts = new ArrayList<>();
    facts.add((en ? "Our pool: " : "本站奖池：") + s.total() + " USDT · " + (en ? s.bettors() + " bettors" : s.bettors() + " 人"));
    List<String> notes = new ArrayList<>();
    if (pm != null) {
      StringBuilder sb = new StringBuilder("Polymarket：");
      for (MarketDataApi.Outcome o : pm.outcomes()) sb.append(o.name()).append(' ').append(pct(o.price())).append("  ");
      facts.add(sb.toString().strip());
      notes.add(en ? "Polymarket data from its public API, not affiliated with Polymarket, fetched " + TIME.format(pm.fetchedAt())
          : "数据来自 Polymarket 公开接口，非 Polymarket 官方，查询时间 " + TIME.format(pm.fetchedAt()));
    }
    notes.add(en ? "Our numbers are real stakes in the pool, not a forecast." : "本站数字是真实押注的奖池比例，不是预测。");
    String title = en ? s.title().en() : "zh-TW".equals(l) ? s.title().zhTW() : s.title().zhCN();
    String stamp = (en ? "Source: our pool, fetched " : "数据来源：本站奖池，查询时间 ") + TIME.format(now);
    String head = (en ? "Viewpoint: " : "观点：") + viewpoint;
    return new CardImageApi.Spec(l, en ? "Compare" : zh(l, "对照"), null, head, title, rows, facts, tw(l, notes), tw(l, stamp));
  }

  // ---------- 小工具 ----------

  private MarketPublicApi.Snapshot market(String no) {
    long n;
    try {
      n = Long.parseLong(no == null ? "" : no.strip());
    } catch (NumberFormatException e) {
      throw new ValidationException(List.of(new ValidationException.Field("marketNo", "required")));
    }
    return markets.snapshot(n).orElseThrow(() -> new BizException("NOT_FOUND", "市场不存在或还是草稿"));
  }

  static String lang(String l) {
    return "zh-CN".equals(l) || "zh-TW".equals(l) ? l : "en";
  }

  private static String zh(String l, String s) {
    return "zh-TW".equals(l) ? Hant.convert(s) : s;
  }

  private static String tw(String l, String s) {
    return "zh-TW".equals(l) ? Hant.convert(s) : s;
  }

  private static List<String> tw(String l, List<String> s) {
    return "zh-TW".equals(l) ? s.stream().map(Hant::convert).toList() : s;
  }

  static double ratio(String pct) {
    try {
      return Math.max(0, Math.min(1, new BigDecimal(pct.replace("%", "").replace("<", "").replace(">", "")).doubleValue() / 100));
    } catch (RuntimeException e) {
      return 0;
    }
  }

  /** 0.62 → 62.0% */
  static String pct(String price) {
    return new BigDecimal(price).multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP).toPlainString() + "%";
  }

  static String dataUrl(byte[] png) {
    return "data:image/png;base64," + Base64.getEncoder().encodeToString(png);
  }

  static List<ContentAdminApi.RefView> views(List<Ref> refs) {
    return refs.stream().map(r -> new ContentAdminApi.RefView(r.key(), r.label(), r.labelEn(), r.value(), r.source(), r.at())).toList();
  }
}
