package com.forecast.app.resolve.data;

import com.forecast.app.resolve.api.MarketDataApi;
import com.forecast.app.resolve.source.SourceException;
import com.forecast.app.resolve.source.SourceGateway;
import com.forecast.app.resolve.source.SourceReply;
import com.forecast.app.resolve.source.SourceRequest;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 公开行情数据（forecast S15，{@link MarketDataApi}）。全部经 {@link SourceGateway#call}（超时、重试、熔断、存档，约定 40），存档里的市场为空。
 * <ul>
 *   <li>1 小时 K 线：币安 {@code /api/v3/klines?interval=1h}（一次最多 1000 根）；拿不到时 OKX {@code /api/v5/market/history-candles?bar=1H}
 *       （一次最多 100 根，超过分页，只用 confirm = 1 的）。和加密裁决共用 binance、okx 两个来源的地址配置，但<b>熔断和存档的来源名分开</b>
 *       （BINANCE_1H、OKX_1H）：这里连续失败不会让加密自动裁决的熔断打开（NF-15：自动化程序出问题不影响结算）。</li>
 *   <li>Polymarket：gamma 公开接口 {@code /markets?slug=}；<b>默认关闭</b>（使用条款还没核实，开发记录 S15），配置 {@code polymarket}。</li>
 * </ul>
 */
@Component
class MarketData implements MarketDataApi {

  static final long HOUR = 3_600_000L;
  static final int MAX_HOURS = 168;
  private static final Pattern SYMBOL = Pattern.compile("[A-Z0-9]{2,12}USDT");
  private static final Pattern SLUG = Pattern.compile("[a-z0-9-]{3,120}");
  private static final ObjectMapper JSON = new ObjectMapper();

  private final SourceGateway gateway;
  private final Clock clock;

  MarketData(SourceGateway gateway, Clock clock) {
    this.gateway = gateway;
    this.clock = clock;
  }

  @Override
  public Hourly hourly(String symbol, int hours) {
    if (symbol == null || !SYMBOL.matcher(symbol).matches()) throw new IllegalArgumentException("交易对不对：" + symbol);
    int n = Math.max(1, Math.min(MAX_HOURS, hours));
    Instant end = clock.instant().truncatedTo(ChronoUnit.HOURS);
    Instant start = end.minus(Duration.ofHours(n));
    Hourly b = binance(symbol, start, end, n);
    if (b.ok()) return b;
    Hourly o = okx(symbol, start, end, n);
    if (o.ok()) return o;
    return new Hourly(symbol, b.source(), false, List.of(), "币安：" + b.error() + "；OKX：" + o.error(), clock.instant(), null);
  }

  private Hourly binance(String symbol, Instant start, Instant end, int n) {
    String label = "Binance " + asset(symbol) + "/USDT";
    if (!gateway.usable("binance")) return new Hourly(symbol, label, false, List.of(), "币安没有打开", clock.instant(), null);
    String base = trim(gateway.config("binance").getBaseUrl());
    URI uri = URI.create(base + "/api/v3/klines?symbol=" + symbol + "&interval=1h&startTime=" + start.toEpochMilli() + "&endTime=" + (end.toEpochMilli() - 1)
        + "&limit=1000");
    SourceRequest<List<Bar>> req = new SourceRequest<>("BINANCE_1H", "binance", uri, uri.toString(), Map.of(), body -> parseBinance(body, start, end, n),
        MarketData::summary);
    SourceReply<List<Bar>> r = gateway.call(req, List.of(), null);
    if (!r.ok()) return new Hourly(symbol, label, false, List.of(), r.error(), r.fetchedAt(), r.bodySha256());
    return new Hourly(symbol, label, true, r.value(), null, r.fetchedAt(), r.bodySha256());
  }

  static List<Bar> parseBinance(String body, Instant start, Instant end, int n) throws SourceException {
    JsonNode root = read(body);
    if (!root.isArray()) throw SourceException.badFormat(root.has("code") ? "错误码 " + root.path("code").asString() : "不是数组");
    Map<Long, Bar> bars = new TreeMap<>();
    for (JsonNode k : root) {
      if (!k.isArray() || k.size() < 7) throw SourceException.badFormat("K 线字段不全");
      long t = millis(k.get(0));
      if (t < start.toEpochMilli() || t >= end.toEpochMilli()) continue;
      if (bars.put(t, new Bar(Instant.ofEpochMilli(t), price(k.get(1)), price(k.get(4)))) != null) throw SourceException.badFormat("K 线时间重复");
    }
    return complete(bars, start, n);
  }

  private Hourly okx(String symbol, Instant start, Instant end, int n) {
    String label = "OKX " + asset(symbol) + "-USDT";
    if (!gateway.usable("okx")) return new Hourly(symbol, label, false, List.of(), "OKX 没有打开", clock.instant(), null);
    String base = trim(gateway.config("okx").getBaseUrl());
    Map<Long, Bar> bars = new TreeMap<>();
    long after = end.toEpochMilli();
    SourceReply<List<Bar>> last = null;
    for (int page = 0; page < 3 && bars.size() < n; page++) {
      URI uri = URI.create(base + "/api/v5/market/history-candles?instId=" + asset(symbol) + "-USDT&bar=1H&after=" + after + "&before="
          + (start.toEpochMilli() - 1) + "&limit=100");
      SourceRequest<List<Bar>> req = new SourceRequest<>("OKX_1H", "okx", uri, uri.toString(), Map.of(), MarketData::parseOkx, MarketData::summary);
      last = gateway.call(req, List.of(), null);
      if (!last.ok()) return new Hourly(symbol, label, false, List.of(), last.error(), last.fetchedAt(), last.bodySha256());
      if (last.value().isEmpty()) break;
      for (Bar b : last.value()) {
        long t = b.openTime().toEpochMilli();
        if (t >= start.toEpochMilli() && t < end.toEpochMilli()) bars.put(t, b);
      }
      after = last.value().stream().map(b -> b.openTime().toEpochMilli()).min(Long::compare).orElse(after);
    }
    try {
      return new Hourly(symbol, label, true, complete(bars, start, n), null, last == null ? clock.instant() : last.fetchedAt(),
          last == null ? null : last.bodySha256());
    } catch (SourceException e) {
      return new Hourly(symbol, label, false, List.of(), e.getMessage(), last == null ? clock.instant() : last.fetchedAt(), null);
    }
  }

  static List<Bar> parseOkx(String body) throws SourceException {
    JsonNode root = read(body);
    if (!root.isObject() || !"0".equals(root.path("code").asString(""))) throw SourceException.badFormat("错误码 " + root.path("code").asString(""));
    List<Bar> out = new ArrayList<>();
    for (JsonNode k : root.path("data")) {
      if (!k.isArray() || k.size() < 9) throw SourceException.badFormat("K 线字段不全");
      if (!"1".equals(k.get(8).asString())) continue; // 没收盘的不要
      out.add(new Bar(Instant.ofEpochMilli(millis(k.get(0))), price(k.get(1)), price(k.get(4))));
    }
    out.sort(Comparator.comparing(Bar::openTime));
    return out;
  }

  /** 必须正好是从 start 起的 n 根整点 K 线 */
  static List<Bar> complete(Map<Long, Bar> bars, Instant start, int n) throws SourceException {
    if (bars.size() != n) throw SourceException.notReady("K 线根数不对：要 " + n + " 根，拿到 " + bars.size() + " 根");
    long t = start.toEpochMilli();
    List<Bar> out = new ArrayList<>(n);
    for (Map.Entry<Long, Bar> e : bars.entrySet()) {
      if (e.getKey() != t) throw SourceException.badFormat("K 线不连续：缺 " + Instant.ofEpochMilli(t));
      out.add(e.getValue());
      t += HOUR;
    }
    return out;
  }

  private static Map<String, Object> summary(List<Bar> bars) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("count", bars.size());
    if (!bars.isEmpty()) {
      m.put("first", bars.get(0).openTime().toString());
      m.put("last", bars.get(bars.size() - 1).openTime().toString());
      m.put("lastClose", bars.get(bars.size() - 1).close().toPlainString());
    }
    return m;
  }

  // =====================================================================
  // Polymarket（默认关闭）
  // =====================================================================

  @Override
  public PolymarketQuote polymarket(String slug) {
    Instant now = clock.instant();
    if (!gateway.usable("polymarket")) return new PolymarketQuote(false, false, slug, null, List.of(), "Polymarket 公开接口没有打开（使用条款待核实）", now);
    if (slug == null || !SLUG.matcher(slug.strip()).matches()) return new PolymarketQuote(true, false, slug, null, List.of(), "slug 格式不对", now);
    String s = slug.strip();
    URI uri = URI.create(trim(gateway.config("polymarket").getBaseUrl()) + "/markets?slug=" + s);
    SourceRequest<PolymarketQuote> req = new SourceRequest<>("POLYMARKET", "polymarket", uri, uri.toString(), Map.of(), body -> parsePolymarket(body, s, now),
        q -> Map.of("question", q.question() == null ? "" : q.question(), "outcomes", q.outcomes().size()));
    SourceReply<PolymarketQuote> r = gateway.call(req, List.of(), null);
    if (!r.ok()) return new PolymarketQuote(true, false, s, null, List.of(), r.error(), r.fetchedAt());
    PolymarketQuote q = r.value();
    return new PolymarketQuote(true, true, s, q.question(), q.outcomes(), null, r.fetchedAt());
  }

  static PolymarketQuote parsePolymarket(String body, String slug, Instant at) throws SourceException {
    JsonNode root = read(body);
    JsonNode m = root.isArray() ? (root.isEmpty() ? null : root.get(0)) : root;
    if (m == null || !m.isObject()) throw SourceException.badFormat("没有这个市场");
    JsonNode names = inner(m.path("outcomes"));
    JsonNode prices = inner(m.path("outcomePrices"));
    if (!names.isArray() || !prices.isArray() || names.size() != prices.size() || names.isEmpty()) throw SourceException.badFormat("outcomes / outcomePrices 不对");
    List<Outcome> out = new ArrayList<>();
    for (int i = 0; i < names.size(); i++) {
      BigDecimal p;
      try {
        p = new BigDecimal(prices.get(i).asString(""));
      } catch (NumberFormatException e) {
        throw SourceException.badFormat("价格不是数字");
      }
      if (p.signum() < 0 || p.compareTo(BigDecimal.ONE) > 0) throw SourceException.badFormat("价格不在 0～1");
      out.add(new Outcome(names.get(i).asString(""), p.stripTrailingZeros().toPlainString()));
    }
    return new PolymarketQuote(true, true, slug, m.path("question").asString(""), out, null, at);
  }

  /** gamma 接口里 outcomes、outcomePrices 是 JSON 字符串（"[\"Yes\",\"No\"]"），也兼容直接是数组 */
  private static JsonNode inner(JsonNode n) throws SourceException {
    if (n.isArray()) return n;
    if (n.isString()) return read(n.asString());
    return n;
  }

  // ---------- 小工具 ----------

  private static JsonNode read(String body) throws SourceException {
    try {
      JsonNode n = JSON.readTree(body == null ? "" : body);
      if (n == null || n.isMissingNode() || n.isNull()) throw SourceException.badFormat("返回为空");
      return n;
    } catch (JacksonException e) {
      throw SourceException.badFormat("不是合法的 JSON");
    }
  }

  private static long millis(JsonNode n) throws SourceException {
    if (n != null && n.isIntegralNumber()) return n.asLong();
    if (n != null && n.isString()) {
      try {
        return Long.parseLong(n.asString().strip());
      } catch (NumberFormatException e) {
        // 落到下面
      }
    }
    throw SourceException.badFormat("时间戳不对");
  }

  private static BigDecimal price(JsonNode n) throws SourceException {
    try {
      BigDecimal v = new BigDecimal(n == null ? "" : n.asString("").strip());
      if (v.signum() <= 0) throw SourceException.badFormat("价格不是正数");
      return v;
    } catch (NumberFormatException e) {
      throw SourceException.badFormat("价格不是数字");
    }
  }

  private static String asset(String symbol) {
    return symbol.substring(0, symbol.length() - 4);
  }

  private static String trim(String base) {
    return base == null ? "" : base.replaceAll("/+$", "");
  }
}
