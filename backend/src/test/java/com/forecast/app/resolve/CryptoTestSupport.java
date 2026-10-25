package com.forecast.app.resolve;

import com.forecast.app.market.api.I18nText;
import com.forecast.app.market.api.MarketAdminApi;
import com.forecast.app.market.autogen.AutogenService;
import com.forecast.app.resolve.auto.CryptoResolver;
import com.forecast.app.resolve.source.ResolveProperties;
import com.forecast.app.resolve.source.SourceGateway;
import com.forecast.app.settle.SettleTestSupport;
import com.forecast.app.support.CandleBook;
import com.forecast.app.support.StubHttp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 加密自动生成、自动裁决测试的公共部分（forecast S6；真实 MySQL 8.4、Redis 7，C37）。沿用 settle 的客户、下注、截止、结算核对（{@link SettleTestSupport}），
 * 加上本机的 HTTP 替身数据源（{@link StubHttp} + {@link CandleBook}，照币安、OKX、Coinbase 的格式回复，<b>不连外网</b>）：
 * 每个测试开始时把三个来源的地址指到替身、清掉熔断状态；结束时指回不监听的端口、关掉第三源和全部自动生成模板。
 */
public abstract class CryptoTestSupport extends SettleTestSupport {

  protected static final StubHttp STUB = new StubHttp();
  protected static final CandleBook BOOK = new CandleBook();

  static {
    STUB.handle(BOOK.handler());
  }

  @Autowired protected CryptoResolver resolver;
  @Autowired protected ResolveProperties resolveProps;
  @Autowired protected SourceGateway gateway;
  @Autowired protected AutogenService autogen;

  @BeforeEach
  void pointSourcesToStub() {
    BOOK.clear();
    STUB.clear();
    resolveProps.getSources().get("binance").setBaseUrl(STUB.url() + "/binance");
    resolveProps.getSources().get("okx").setBaseUrl(STUB.url() + "/okx");
    resolveProps.getSources().get("coinbase").setBaseUrl(STUB.url() + "/coinbase");
    resolveProps.getSources().get("coinbase").setEnabled(false);
    gateway.resetBreakers();
  }

  @AfterEach
  void restoreSources() {
    for (String k : List.of("binance", "okx", "coinbase")) resolveProps.getSources().get(k).setBaseUrl("http://127.0.0.1:9");
    resolveProps.getSources().get("coinbase").setEnabled(false);
    gateway.resetBreakers();
    jdbc.update("UPDATE market_autogen SET enabled = 0");
  }

  /** 一个加密 1 小时涨跌市场（手工建、上架，和自动生成的一样带 source_ref）：选项 0 = 涨、1 = 跌；开放 = 现在 − 1 分钟，截止 = 窗口开始 − 30 秒 */
  protected Market cryptoMarket(Instant windowStart) {
    return cryptoMarket(windowStart, Duration.ofHours(1), refOf("BTCUSDT"));
  }

  protected Map<String, Object> refOf(String symbol) {
    Map<String, Object> ref = new LinkedHashMap<>();
    ref.put("kind", "CRYPTO_UPDOWN");
    ref.put("symbol", symbol);
    ref.put("options", Map.of("UP", 1, "DOWN", 2));
    return ref;
  }

  protected Market cryptoMarket(Instant windowStart, Duration period, Map<String, Object> sourceRef) {
    Instant open = Instant.now(clock).minus(Duration.ofMinutes(1));
    Instant close = windowStart.minusSeconds(30);
    List<MarketAdminApi.OptionInput> opts = List.of(new MarketAdminApi.OptionInput(tx("涨"), tx("结束价 ≥ 开始价")),
        new MarketAdminApi.OptionInput(tx("跌"), tx("结束价 < 开始价")));
    MarketAdminApi.DraftInput in = new MarketAdminApi.DraftInput(period.toMinutes() == 15 ? "CRYPTO_15M" : "CRYPTO_1H", tx("BTC 涨还是跌"),
        tx("以币安现货 BTC/USDT 的 1 分钟 K 线为准"), "AUTO", "Binance", "OKX", sourceRef, open.toString(), close.toString(), windowStart.toString(),
        windowStart.plus(period).toString(), null, null, null, opts);
    MarketAdminApi.Detail d = admin.createDraft(in, 1);
    admin.publish(Long.parseLong(d.publicNo()), true, 1);
    long id = jdbc.queryForObject("SELECT id FROM market_question WHERE public_no = ?", Long.class, Long.parseLong(d.publicNo()));
    return new Market(id, d.publicNo(), d.options().stream().map(MarketAdminApi.OptionView::optionNo).toList());
  }

  protected static I18nText tx(String s) {
    return new I18nText(s + " en", s + " 简", s + " 繁");
  }

  /** 两边各有一个客户各押 10（否则截止时只有一个选项有人押，按 E3 / E4 直接作废，到不了待裁决） */
  protected User[] bothSides(Market m) throws Exception {
    User up = user("100");
    User down = user("100");
    bet(up.id(), m, 0, "10");
    bet(down.id(), m, 1, "10");
    return new User[] {up, down};
  }

  /** 两个来源都设同样的开始价、结束价 */
  protected void prices(Instant windowStart, String open, String close) {
    BOOK.price("BINANCE", "BTCUSDT", windowStart, open, close);
    BOOK.price("OKX", "BTCUSDT", windowStart, open, close);
  }

  /** 窗口开始时间（截止 = 开始 − 30 秒）：现在 + 1 小时的整点之后 */
  protected Instant nextWindow() {
    long h = 3_600_000L;
    return Instant.ofEpochMilli((Instant.now(clock).toEpochMilli() / h + 2) * h);
  }

  /** 截止、事件结束（进入待裁决），再拨到窗口结束 + 1 分钟（第一次取数的时间） */
  protected void endAndWaitFirstFetch(Market m) {
    closeAndEnd(m);
    Instant end = jdbc.queryForObject("SELECT event_end_at FROM market_question WHERE id = ?", java.sql.Timestamp.class, m.id()).toLocalDateTime()
        .toInstant(java.time.ZoneOffset.UTC);
    Instant first = end.plus(Duration.ofMinutes(1));
    Instant now = Instant.now(clock);
    if (now.isBefore(first)) advance(Duration.between(now, first));
  }

  protected String taskStatus(Market m) {
    List<String> s = jdbc.queryForList("SELECT status FROM resolve_auto_task WHERE market_id = ?", String.class, m.id());
    return s.isEmpty() ? null : s.get(0);
  }

  protected int fetchRows(Market m, String source) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM resolve_source_fetch WHERE market_id = ? AND source = ?", Integer.class, m.id(), source);
  }

  protected List<String> recordKinds(Market m) {
    return jdbc.queryForList("SELECT kind FROM resolve_record WHERE market_id = ? ORDER BY id", String.class, m.id());
  }
}
