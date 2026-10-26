package com.forecast.app.market.card;

import com.forecast.app.common.error.BizException;
import com.forecast.app.market.api.I18nText;
import com.forecast.app.market.api.MarketApi;
import com.forecast.app.market.api.MarketStatus;
import com.forecast.app.market.core.ClientViews;
import com.forecast.app.market.core.MarketPublicService;
import com.forecast.app.market.core.MarketService;
import com.forecast.app.market.pub.PublicContent;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 分享卡片（forecast S14，MF-5；需求说明书 v1.3.2 第 5.2 节"分享卡片和挑战卡"、第 6.3 节、AC-86、AC-87；架构方案第 18 节）。
 * <p>五种卡片，内容全部取自系统（奖池、人数、概率、预计赔率、结算合计），模板文字在 {@code content/cards.json}：
 * <ul>
 *   <li><b>市场卡</b>（market）：题目、各选项的概率、人数、金额、预计赔率、奖池、截止时间。市场已结束时自动换成开奖卡。</li>
 *   <li><b>开奖卡</b>（result）：结果、依据（加密：开始价和结束价、来源；体育：比分；平台裁决：裁决人编号）、奖池、派彩合计、抽成、尾差、下注人数；
 *       作废 / 取消写原因和"已全额退款"。数字和开奖记录（{@link MarketPublicService#result}）同一份（AC-88）。</li>
 *   <li><b>挑战卡</b>（challenge，客户生成）："我押了 X，谁敢押 Y？"、两边人数、对面当前预计赔率；对面 0 人时照实写（AC-87）。</li>
 *   <li><b>我押中的开奖卡</b>（win，客户生成）：开奖卡 + "我押中了 X"，派彩金额默认不显示，客户选择显示才显示（AC-86）。</li>
 *   <li><b>封盘倒计时卡</b>（countdown，系统生成，第 6.3 节，F-16 草稿）：距截止几分钟、两边人数和金额、当前预计赔率、"以截止时为准"、
 *       只有一边有人押时全额退款。只在可以下注、还没截止时有。自动发布（MQ-2、MF-7）这次不做。</li>
 *   <li><b>周报卡</b>（weekly）：场数、有效场数、作废或取消场数和原因、奖池、派彩、抽成、退款、尾差合计。</li>
 * </ul>
 * 客户生成的两种卡片用随机凭证（表 {@code market_share}），链接和图片地址里只有凭证，<b>不带客户的任何信息</b>；生成时核对这个客户确实押了 / 押中了。
 * <p>图片：{@link CardPainter} 画 1200 × 630 PNG。按内容的指纹缓存最近 {@code forecast.cards.cache-size} 张（数字没变就不重画），
 * 同时最多画 {@code forecast.cards.max-concurrent} 张（画一张约几十毫秒，排队超过 5 秒返回 503）。
 */
@Service
public class CardService {

  public static final List<String> KINDS = List.of("market", "result", "challenge", "win", "countdown", "weekly");
  /** 图片尺寸（Open Graph 的 og:image:width / height） */
  public static final int WIDTH = CardPainter.W;
  public static final int HEIGHT = CardPainter.H;
  static final int MAX_ROWS = 4;
  private static final DateTimeFormatter UTC = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'", Locale.ROOT).withZone(ZoneOffset.UTC);
  private static final DateTimeFormatter LOCAL8 = DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.ROOT).withZone(ZoneOffset.ofHours(8));
  private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("MM-dd", Locale.ROOT).withZone(ZoneOffset.UTC);
  private static final SecureRandom RANDOM = new SecureRandom();

  private final MarketService markets;
  private final MarketPublicService pub;
  private final PublicContent content;
  private final CardTexts texts;
  private final CardPainter painter;
  private final ShareMapper shares;
  private final ObjectMapper json;
  private final Clock clock;
  private final Semaphore painting;
  private final Map<String, Image> cache;

  CardService(MarketService markets, MarketPublicService pub, PublicContent content, CardTexts texts, CardPainter painter, ShareMapper shares,
      ObjectMapper json, Clock clock, @Value("${forecast.cards.max-concurrent:2}") int maxConcurrent, @Value("${forecast.cards.cache-size:64}") int cacheSize) {
    this.markets = markets;
    this.pub = pub;
    this.content = content;
    this.texts = texts;
    this.painter = painter;
    this.shares = shares;
    this.json = json;
    this.clock = clock;
    this.painting = new Semaphore(Math.max(1, maxConcurrent));
    int size = Math.max(1, cacheSize);
    this.cache = java.util.Collections.synchronizedMap(new LinkedHashMap<>(size, 0.75f, true) {
      @Override
      protected boolean removeEldestEntry(Map.Entry<String, Image> e) {
        return size() > size;
      }
    });
  }

  // =====================================================================
  // 图片
  // =====================================================================

  /** 一张画好的图：PNG、ETag（内容指纹）、内容是否不会再变（已结束的市场、过完的周：可以缓存久一点） */
  public record Image(byte[] png, String etag, boolean settled) {}

  /** 画图排队太久（同时请求太多）：控制器返回 503 */
  public static final class BusyException extends RuntimeException {
    BusyException() {
      super("卡片生成繁忙");
    }
  }

  public Image image(CardModel m) {
    String key = sha256(m.fingerprint());
    Image hit = cache.get(key);
    if (hit != null) return hit;
    try {
      if (!painting.tryAcquire(5, TimeUnit.SECONDS)) throw new BusyException();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new BusyException();
    }
    try {
      Image again = cache.get(key);
      if (again != null) return again;
      boolean done = "result".equals(m.kind()) || "win".equals(m.kind()) || ("weekly".equals(m.kind()) && m.stamp() != null && m.status() != null
          && m.status().equals(texts.t(m.lang(), "weekly.complete")));
      Image img = new Image(painter.png(m), "\"" + key.substring(0, 32) + "\"", done);
      cache.put(key, img);
      return img;
    } finally {
      painting.release();
    }
  }

  /** 一种卡片一种语言的模板文字的指纹（forecast S16，自动发布的模板审定；见 {@link CardTexts#fingerprint}） */
  public String templateFingerprint(String kind, String lang) {
    return texts.fingerprint(kind, lang);
  }

  // =====================================================================
  // 各种卡片的内容
  // =====================================================================

  /** 公开的卡片：market（结束了换成 result）、result、countdown；不存在、草稿、不适用（例如没开放的倒计时卡）为空 */
  public Optional<CardModel> publicCard(String kind, long publicNo, String lang) {
    String l = PublicContent.lang(lang);
    return switch (kind) {
      case "market" -> {
        Optional<ClientViews.Detail> d = markets.clientDetail(publicNo, null);
        if (d.isEmpty()) yield Optional.empty();
        yield ended(d.get().status()) ? resultCard(publicNo, l, null, null) : Optional.of(marketCard(d.get(), l));
      }
      case "result" -> resultCard(publicNo, l, null, null);
      case "countdown" -> markets.clientDetail(publicNo, null).flatMap(d -> countdownCard(d, l));
      default -> Optional.empty();
    };
  }

  /** 周报卡；周的写法不对或在将来为空 */
  public Optional<CardModel> weeklyCard(String week, String lang) {
    MarketPublicService.Week w;
    try {
      w = pub.week(week);
    } catch (RuntimeException e) {
      return Optional.empty();
    }
    if (Instant.parse(w.from()).isAfter(clock.instant())) return Optional.empty();
    return Optional.of(weekly(w, PublicContent.lang(lang)));
  }

  /** 客户生成的卡片（凭证）：挑战卡、我押中的开奖卡 */
  public Optional<CardModel> tokenCard(String token, String lang) {
    if (token == null || !token.matches("[A-Za-z0-9_-]{22}")) return Optional.empty();
    ShareMapper.Row s = shares.byToken(token);
    if (s == null) return Optional.empty();
    String l = PublicContent.lang(lang);
    String optionNo = optionNo(s.marketId, s.optionId);
    if (optionNo == null) return Optional.empty();
    if ("CHALLENGE".equals(s.kind)) {
      return markets.clientDetail(s.publicNo, null).map(d -> challengeCard(d, optionNo, l));
    }
    return resultCard(s.publicNo, l, optionNo, s.showAmount && s.amount != null ? money(s.amount) : null);
  }

  /** 这个凭证属于哪个市场（Open Graph：链接里的 card 必须是这个市场的） */
  public Optional<Long> tokenMarket(String token) {
    if (token == null || !token.matches("[A-Za-z0-9_-]{22}")) return Optional.empty();
    ShareMapper.Row s = shares.byToken(token);
    return s == null ? Optional.empty() : Optional.of(s.publicNo);
  }

  private String optionNo(long marketId, long optionId) {
    return markets.options(marketId).stream().filter(o -> o.optionId() == optionId).findFirst().map(o -> String.valueOf(o.optionNo())).orElse(null);
  }

  private CardModel marketCard(ClientViews.Detail d, String l) {
    JsonNode pool = tree(d.pool());
    List<CardModel.Row> rows = optionRows(d, pool, l, null, null);
    List<String> facts = new ArrayList<>();
    facts.add(poolLine(pool, d.feeRate(), l));
    facts.add(timeLine(d, l));
    moreLine(d, l, facts);
    List<String> notes = new ArrayList<>();
    boolean frozen = pool != null && pool.path("frozen").asBoolean(false);
    notes.add(texts.t(l, frozen ? "finalNote" : "oddsNote"));
    if (!frozen) notes.add(texts.t(l, "oneSide"));
    return new CardModel("market", l, content.brand(), category(d.category(), l), statusLabel(d, l), null, pick(d.title(), l), rows, facts, notes, nowStamp(l),
        CardModel.Tone.PRIMARY);
  }

  private CardModel challengeCard(ClientViews.Detail d, String mineNo, String l) {
    JsonNode pool = tree(d.pool());
    List<CardModel.Row> rows = optionRows(d, pool, l, mineNo, null);
    ClientViews.OptionDetail mine = d.options().stream().filter(o -> o.optionNo().equals(mineNo)).findFirst().orElse(d.options().get(0));
    List<ClientViews.OptionDetail> others = d.options().stream().filter(o -> !o.optionNo().equals(mineNo)).toList();
    boolean two = others.size() == 1;
    String headline = two ? texts.t(l, "challenge.headline", Map.of("mine", pick(mine.label(), l), "other", pick(others.get(0).label(), l)))
        : texts.t(l, "challenge.headlineMulti", Map.of("mine", pick(mine.label(), l)));
    boolean othersEmpty = others.stream().allMatch(o -> bettors(pool, o.optionNo()) == 0);
    List<String> facts = new ArrayList<>();
    List<String> notes = new ArrayList<>();
    if (othersEmpty) {
      facts.add(texts.t(l, "challenge.otherZero"));
    } else if (two) {
      String mult = text(option(pool, others.get(0).optionNo()), "mult", "—");
      if (!"—".equals(mult)) facts.add(texts.t(l, "challenge.other", Map.of("other", pick(others.get(0).label(), l), "mult", mult, "fee", d.feeRate())));
    }
    facts.add(poolLine(pool, d.feeRate(), l));
    facts.add(timeLine(d, l));
    moreLine(d, l, facts);
    if (!othersEmpty) {
      notes.add(texts.t(l, "oddsNote"));
      notes.add(texts.t(l, "oneSide"));
    }
    return new CardModel("challenge", l, content.brand(), category(d.category(), l), statusLabel(d, l), headline, pick(d.title(), l), rows, facts, notes,
        nowStamp(l), toneOf(d, mine));
  }

  private Optional<CardModel> countdownCard(ClientViews.Detail d, String l) {
    Instant now = clock.instant();
    Instant open = Instant.parse(d.openAt());
    Instant close = Instant.parse(d.closeAt());
    if (!"listed".equals(d.status()) || now.isBefore(open) || !now.isBefore(close)) return Optional.empty();
    long minutes = Math.max(1, Duration.between(now, close).toMinutes());
    JsonNode pool = tree(d.pool());
    List<CardModel.Row> rows = optionRows(d, pool, l, null, null);
    List<String> facts = new ArrayList<>();
    facts.add(poolLine(pool, d.feeRate(), l));
    facts.add(timeLine(d, l));
    moreLine(d, l, facts);
    List<String> notes = new ArrayList<>();
    // 人少时照实写（第 6.3 节：不因为数字难看就不发）：二选一里有一边 0 人
    List<ClientViews.OptionDetail> empty = d.options().size() == 2 ? d.options().stream().filter(o -> bettors(pool, o.optionNo()) == 0).toList() : List.of();
    notes.add(texts.t(l, "oddsNote"));
    // 一边 0 人：放在关键数字的第一行（正常字号），不放在小字说明里
    if (empty.size() == 1) facts.add(0, texts.t(l, "countdown.zero", Map.of("other", pick(empty.get(0).label(), l))));
    else notes.add(texts.t(l, "oneSide"));
    return Optional.of(new CardModel("countdown", l, content.brand(), category(d.category(), l), statusLabel(d, l),
        texts.t(l, "countdown.headline", Map.of("minutes", String.valueOf(minutes))), pick(d.title(), l), rows, facts, notes, nowStamp(l), CardModel.Tone.PRIMARY));
  }

  /** 开奖卡；winNo 不为空时是"我押中了"的版本（payout 不为空时显示我的派彩） */
  private Optional<CardModel> resultCard(long publicNo, String l, String winNo, String payout) {
    Optional<MarketPublicService.Result> or = pub.result(publicNo);
    if (or.isEmpty()) return Optional.empty();
    MarketPublicService.Result r = or.get();
    JsonNode settle = tree(r.settle());
    JsonNode res = tree(r.resolution());
    List<CardModel.Row> rows = new ArrayList<>();
    List<String> facts = new ArrayList<>();
    List<String> notes = new ArrayList<>();
    String headline;
    CardModel.Tone tone;
    if ("settled".equals(r.status())) {
      ClientViews.Detail d = markets.clientDetail(publicNo, null).orElse(null);
      JsonNode pool = d == null ? null : tree(d.pool());
      String win = r.options().stream().filter(o -> o.optionNo().equals(r.winningOptionNo())).findFirst().map(o -> pick(o.label(), l)).orElse("—");
      headline = texts.t(l, "result.headline", Map.of("result", win));
      tone = CardModel.Tone.UP;
      if (d != null) rows = optionRows(d, pool, l, r.winningOptionNo(), r.winningOptionNo());
      if (settle != null && "payout".equals(settle.path("kind").asString(""))) {
        facts.add(texts.t(l, "result.totals", Map.of("pool", money(settle.path("poolTotal").asString("0")), "payout", money(settle.path("payoutTotal").asString("0")),
            "fee", money(settle.path("feeTotal").asString("0")), "dust", money(settle.path("dust").asString("0")))));
      }
      facts.add(texts.t(l, "result.bettors", Map.of("bettors", String.valueOf(r.bettors()))));
      String basis = basis(res, l);
      if (basis != null) facts.add(basis);
      if (d != null) moreLine(d, l, facts);
    } else {
      headline = texts.t(l, "result." + r.status());
      tone = CardModel.Tone.NEUTRAL;
      String reason = "voided".equals(r.status()) ? r.voidReason() : r.cancelReason();
      if (reason != null && !reason.isBlank()) facts.add(texts.t(l, "result.reason", Map.of("reason", reason(reason, l))));
      String refund = settle != null && "refund".equals(settle.path("kind").asString("")) ? money(settle.path("payoutTotal").asString("0")) : null;
      if (refund != null) facts.add(texts.t(l, "result.refund", Map.of("refund", refund)));
      facts.add(texts.t(l, "result.bettors", Map.of("bettors", String.valueOf(r.bettors()))));
      notes.add(texts.t(l, "result.refundNote"));
    }
    String kind = "result";
    if (winNo != null && "settled".equals(r.status())) {
      kind = "win";
      String opt = r.options().stream().filter(o -> o.optionNo().equals(winNo)).findFirst().map(o -> pick(o.label(), l)).orElse("—");
      headline = texts.t(l, "win.headline", Map.of("option", opt));
      if (payout != null) facts.add(0, texts.t(l, "win.payout", Map.of("payout", payout)));
    }
    String ended = r.endedAt() == null ? null : texts.t(l, "endedStamp", Map.of("time", utc(r.endedAt())));
    return Optional.of(new CardModel(kind, l, content.brand(), category(r.category(), l), texts.t(l, "status." + r.status()), headline, pick(r.title(), l), rows,
        facts, notes, ended == null ? nowStamp(l) : ended, tone));
  }

  private CardModel weekly(MarketPublicService.Week w, String l) {
    Instant from = Instant.parse(w.from());
    Instant to = Instant.parse(w.to());
    String title = texts.t(l, "weekly.title", Map.of("week", w.week(), "from", DAY.format(from), "to", DAY.format(to.minusSeconds(1))));
    List<CardModel.Row> rows = new ArrayList<>();
    rows.add(stat(texts.t(l, "weekly.markets"), String.valueOf(w.markets())));
    rows.add(stat(texts.t(l, "weekly.settled"), String.valueOf(w.settled())));
    rows.add(stat(texts.t(l, "weekly.voided"), String.valueOf(w.voided() + w.cancelled())));
    rows.add(stat(texts.t(l, "weekly.pool"), money(w.poolTotal())));
    rows.add(stat(texts.t(l, "weekly.payout"), money(w.payoutTotal())));
    rows.add(stat(texts.t(l, "weekly.fee"), money(w.feeTotal())));
    rows.add(stat(texts.t(l, "weekly.refund"), money(w.refundTotal())));
    rows.add(stat(texts.t(l, "weekly.dust"), money(w.dust())));
    List<String> facts = new ArrayList<>();
    if (!w.reasons().isEmpty()) {
      // 原因按场数从多到少，卡片上最多写 3 种（其余在周报页里看全），后面加"…"
      List<MarketPublicService.Reason> rs = new ArrayList<>(w.reasons());
      rs.sort(java.util.Comparator.comparingInt(MarketPublicService.Reason::count).reversed());
      List<String> parts = new ArrayList<>();
      for (MarketPublicService.Reason r : rs.subList(0, Math.min(3, rs.size()))) {
        parts.add((r.reason() == null ? texts.t(l, "status." + r.status()) : reason(r.reason(), l)) + " × " + r.count());
      }
      String joined = String.join(l.equals("en") ? "; " : "；", parts) + (rs.size() > 3 ? " …" : "");
      facts.add(texts.t(l, "weekly.reasons", Map.of("reasons", joined)));
    }
    String status = texts.t(l, w.complete() ? "weekly.complete" : "weekly.inProgress");
    String stamp = w.complete() ? texts.t(l, "endedStamp", Map.of("time", UTC.format(to))) : nowStamp(l);
    return new CardModel("weekly", l, content.brand(), texts.t(l, "weekly.chip"), status, null, title, rows, facts, List.of(), stamp, CardModel.Tone.PRIMARY);
  }

  private static CardModel.Row stat(String label, String value) {
    return new CardModel.Row(label, null, -1, value, CardModel.Tone.NEUTRAL, false);
  }

  // =====================================================================
  // 客户生成的卡片：凭证
  // =====================================================================

  /** 生成好的凭证 */
  public record Share(String token, String kind, long publicNo) {}

  /**
   * 挑战卡：这个客户在这个市场的这个选项上有有效持仓、市场正在开放下注；同一客户同一市场同一选项沿用同一个凭证。
   * 不满足时 400 {@code CARD_NOT_AVAILABLE}。
   */
  public Share challenge(long userId, long publicNo, String optionNo) {
    ClientViews.Detail d = markets.clientDetail(publicNo, userId).orElseThrow(CardService::notFound);
    if (!"listed".equals(d.status()) || !hasPosition(tree(d.pool()), optionNo)) throw new BizException("CARD_NOT_AVAILABLE", "只能为自己押过、还在下注期的选项生成挑战卡");
    return issue(userId, publicNo, "CHALLENGE", optionNo, false, null);
  }

  /**
   * 我押中的开奖卡：市场已结算、这个客户押了结果选项；showAmount 时把本人的派彩（结算明细，已扣抽成）记下来显示在卡片上，默认不显示（AC-86）。
   */
  public Share win(long userId, long publicNo, boolean showAmount) {
    ClientViews.Detail d = markets.clientDetail(publicNo, userId).orElseThrow(CardService::notFound);
    JsonNode pool = tree(d.pool());
    if (!"settled".equals(d.status()) || d.winningOptionNo() == null || !hasPosition(pool, d.winningOptionNo())) {
      throw new BizException("CARD_NOT_AVAILABLE", "只能为押中的已结算市场生成开奖卡");
    }
    BigDecimal amount = null;
    if (showAmount) {
      JsonNode mine = tree(d.settle()) == null ? null : tree(d.settle()).path("mine");
      if (mine == null || !mine.hasNonNull("payout")) throw new BizException("CARD_NOT_AVAILABLE", "还没有派彩记录");
      amount = new BigDecimal(mine.path("payout").asString("0"));
    }
    return issue(userId, publicNo, "WIN", d.winningOptionNo(), showAmount, amount);
  }

  private Share issue(long userId, long publicNo, String kind, String optionNo, boolean showAmount, BigDecimal amount) {
    MarketApi.MarketRef m = markets.find(publicNo).orElseThrow(CardService::notFound);
    long optionId = markets.options(m.marketId()).stream().filter(o -> String.valueOf(o.optionNo()).equals(optionNo)).findFirst()
        .map(MarketApi.OptionRef::optionId).orElseThrow(CardService::notFound);
    ShareMapper.Row row = shares.find(userId, m.marketId(), kind, optionId, showAmount);
    if (row == null) {
      shares.insertIgnore(newToken(), m.marketId(), userId, kind, optionId, showAmount, amount, LocalDateTime.now(clock.withZone(ZoneOffset.UTC)));
      row = shares.find(userId, m.marketId(), kind, optionId, showAmount);
    }
    return new Share(row.token, kind.toLowerCase(Locale.ROOT), publicNo);
  }

  private static BizException notFound() {
    return new BizException("NOT_FOUND", "市场或选项不存在");
  }

  static String newToken() {
    byte[] b = new byte[16];
    RANDOM.nextBytes(b);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
  }

  private static boolean hasPosition(JsonNode pool, String optionNo) {
    if (pool == null || optionNo == null) return false;
    for (JsonNode m : pool.path("mine")) {
      if (optionNo.equals(m.path("optionNo").asString("")) && new BigDecimal(m.path("amount").asString("0")).signum() > 0) return true;
    }
    return false;
  }

  // =====================================================================
  // 小工具
  // =====================================================================

  /** 选项行：最多 4 个；mark 的那一行打 ✓；winNo 不为空时（开奖卡）结果那一行绿色、其余中性 */
  private List<CardModel.Row> optionRows(ClientViews.Detail d, JsonNode pool, String l, String markNo, String winNo) {
    boolean frozen = pool != null && pool.path("frozen").asBoolean(false);
    List<CardModel.Row> rows = new ArrayList<>();
    for (ClientViews.OptionDetail o : d.options()) {
      if (rows.size() == MAX_ROWS) break;
      JsonNode op = option(pool, o.optionNo());
      String pct = pct(text(op, "pct", "—"));
      String mult = text(op, "mult", "—");
      Map<String, String> v = Map.of("bettors", String.valueOf(op == null ? 0 : op.path("bettors").asInt(0)), "amount", money(text(op, "amount", "0")), "mult", mult);
      String detail = "—".equals(mult) ? texts.t(l, "rowNoOdds", v) : texts.t(l, frozen ? "rowFinal" : "row", v);
      CardModel.Tone tone = winNo != null ? (o.optionNo().equals(winNo) ? CardModel.Tone.UP : CardModel.Tone.NEUTRAL) : toneOf(d, o);
      rows.add(new CardModel.Row(pick(o.label(), l), pct, ratio(pct), detail, tone, o.optionNo().equals(markNo)));
    }
    return rows;
  }

  private void moreLine(ClientViews.Detail d, String l, List<String> facts) {
    if (d.options().size() > MAX_ROWS) facts.add(texts.t(l, "more", Map.of("n", String.valueOf(d.options().size() - MAX_ROWS))));
  }

  /** 只有加密"涨 / 跌"用绿红（选项 1 涨、2 跌，PQ-1），其他用主色 */
  private static CardModel.Tone toneOf(ClientViews.Detail d, ClientViews.OptionDetail o) {
    boolean updown = "CRYPTO_15M".equals(d.category()) || "CRYPTO_1H".equals(d.category());
    if (!updown) return CardModel.Tone.PRIMARY;
    return o.seq() == 1 ? CardModel.Tone.UP : o.seq() == 2 ? CardModel.Tone.DOWN : CardModel.Tone.PRIMARY;
  }

  private String poolLine(JsonNode pool, String feeRate, String l) {
    return texts.t(l, "pool", Map.of("pool", money(text(pool, "total", "0")), "bettors", String.valueOf(pool == null ? 0 : pool.path("bettors").asInt(0)), "fee",
        feeRate == null ? "—" : feeRate));
  }

  /** 截止时间（还没开放的写开放时间）：UTC，中文加北京时间 / UTC+8 */
  private String timeLine(ClientViews.Detail d, String l) {
    boolean upcoming = "listed".equals(d.status()) && Instant.parse(d.openAt()).isAfter(clock.instant());
    String iso = upcoming ? d.openAt() : d.closeAt();
    Instant t = Instant.parse(iso);
    String local = texts.t(l, "local", Map.of("time", LOCAL8.format(t)));
    return texts.t(l, upcoming ? "opens" : "closes", Map.of("utc", UTC.format(t), "local", local));
  }

  private String basis(JsonNode res, String l) {
    if (res == null) return null;
    if (res.hasNonNull("startPrice") && res.hasNonNull("endPrice")) {
      return texts.t(l, "result.crypto", Map.of("start", plain(res.path("startPrice").asString()), "end", plain(res.path("endPrice").asString()), "source",
          res.path("source").asString("—")));
    }
    if (res.hasNonNull("homeScore") && res.hasNonNull("awayScore")) {
      return texts.t(l, "result.sports", Map.of("home", res.path("home").asString(""), "away", res.path("away").asString(""), "homeScore",
          res.path("homeScore").asString(), "awayScore", res.path("awayScore").asString(), "source", res.path("source").asString("—")));
    }
    if ("platform".equals(res.path("mode").asString("")) && res.hasNonNull("resolvedBy")) {
      return texts.t(l, "result.platform", Map.of("by", res.path("resolvedBy").asString()));
    }
    return null;
  }

  private String reason(String r, String l) {
    return texts.has(l, "reasons." + r) ? texts.t(l, "reasons." + r) : r;
  }

  private String category(String c, String l) {
    return c == null ? "" : texts.t(l, "category." + c);
  }

  private String statusLabel(ClientViews.Detail d, String l) {
    boolean upcoming = "listed".equals(d.status()) && Instant.parse(d.openAt()).isAfter(clock.instant());
    return texts.t(l, "status." + (upcoming ? "upcoming" : d.status()));
  }

  private String nowStamp(String l) {
    return texts.t(l, "stamp", Map.of("time", UTC.format(clock.instant())));
  }

  private static boolean ended(String status) {
    MarketStatus s = MarketStatus.ofApi(status);
    return s == MarketStatus.SETTLED || s == MarketStatus.VOIDED || s == MarketStatus.CANCELLED;
  }

  private JsonNode tree(Object o) {
    return o == null ? null : json.valueToTree(o);
  }

  private static JsonNode option(JsonNode pool, String optionNo) {
    if (pool == null) return null;
    for (JsonNode o : pool.path("options")) if (optionNo.equals(o.path("optionNo").asString(""))) return o;
    return null;
  }

  private static int bettors(JsonNode pool, String optionNo) {
    JsonNode o = option(pool, optionNo);
    return o == null ? 0 : o.path("bettors").asInt(0);
  }

  private static String text(JsonNode n, String field, String fallback) {
    if (n == null || !n.hasNonNull(field)) return fallback;
    String v = n.path(field).asString(fallback);
    return v == null || v.isBlank() ? fallback : v;
  }

  static String pct(String p) {
    if (p == null || p.isBlank() || p.equals("—")) return "—";
    return p.endsWith("%") ? p : p + "%";
  }

  /** 概率条的长度：<1% 画一点点，>99% 差一点点满 */
  static double ratio(String pct) {
    if (pct == null || pct.equals("—")) return 0;
    String v = pct.replace("%", "").strip();
    try {
      if (v.startsWith("<")) return 0.005;
      if (v.startsWith(">")) return 0.995;
      return Math.max(0, Math.min(1, Double.parseDouble(v) / 100.0));
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  /** 金额：两位小数、千分位（"1,234.50"）；系统的金额都已经是 2 位小数，这里只加千分位（向下取整和结算一致） */
  static String money(String v) {
    try {
      return money(new BigDecimal(v));
    } catch (RuntimeException e) {
      return v;
    }
  }

  static String money(BigDecimal v) {
    DecimalFormat f = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.ROOT));
    return f.format(v.setScale(2, RoundingMode.DOWN));
  }

  /** 价格原样（去掉多余的 0，不加千分位，和详情页一致） */
  private static String plain(String v) {
    try {
      return new BigDecimal(v).stripTrailingZeros().toPlainString();
    } catch (RuntimeException e) {
      return v;
    }
  }

  static String utc(String iso) {
    try {
      return UTC.format(Instant.parse(iso));
    } catch (RuntimeException e) {
      return iso;
    }
  }

  static String pick(I18nText t, String lang) {
    if (t == null) return "";
    String v = switch (lang) {
      case "zh-CN" -> t.zhCN();
      case "zh-TW" -> t.zhTW() == null || t.zhTW().isBlank() ? t.zhCN() : t.zhTW();
      default -> t.en();
    };
    return v == null || v.isBlank() ? (t.en() == null ? "" : t.en()) : v;
  }

  static String sha256(String s) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
