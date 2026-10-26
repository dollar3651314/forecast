package com.forecast.app.growth.board;

import com.forecast.app.common.health.AlertCheck;
import com.forecast.app.common.http.ExternalHttp;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 数据页第 ① 块（MF-17，需求第 5.2 节 P13、AC-96）：BTC 现价和"过去 N 小时 BTC 每小时涨跌的比例"，来自币安公开行情（1 小时 K 线）。
 * <ul>
 *   <li><b>口径</b>：取最近 N 根<b>已经收盘</b>的 1 小时 K 线（收盘时间早于现在），收盘价 ≥ 开盘价记"涨"、否则"跌"——和我们 BTC 场次的判定规则一样
 *       （正好相等判涨，见加密涨跌的规则文本模板）；比例 = 涨的根数 ÷ N，1 位小数（四舍五入）。现价 = 最新一根（还没收盘的）的收盘价。</li>
 *   <li><b>不在请求里取</b>：{@link GrowthJobs} 每分钟刷新一次放在内存里（每个实例各自一份，只是缓存，不是业务状态），页面、机器人直接读；
 *       刷新失败时保留上一次的数据和它的更新时间（页面照实显示更新时间），从来没取到过时显示"暂时取不到"。</li>
 *   <li><b>外部调用</b>（C81）：{@link ExternalHttp} 的连接 / 读取超时、响应体上限；重试只针对超时、断开、5xx、429；连续失败到阈值后熔断一段时间。
 *       连续 3 次刷新失败时健康检查 {@code alerts} 的 {@code board.binance} 为 DOWN（不影响 {@code status}，约定 11）。</li>
 * </ul>
 */
@Component
@EnableConfigurationProperties(BoardProperties.class)
public class BtcBoard implements AlertCheck {

  private static final Logger log = LoggerFactory.getLogger(BtcBoard.class);
  static final int MAX_BYTES = 256 * 1024;
  /** 连续几次刷新失败算告警 */
  static final int ALERT_AFTER = 3;

  /**
   * 一次取到的结果。
   *
   * @param price 现价（币安原样的字符串去掉多余的 0，USDT）
   * @param up / down 过去 hours 根已收盘 K 线里涨 / 跌的根数
   * @param upPct / downPct 比例（1 位小数，"58.3"）
   * @param from / to 统计的第一根的开盘时间、最后一根的收盘时间
   * @param fetchedAt 取到的时间
   */
  public record Snapshot(String symbol, String price, int hours, int up, int down, String upPct, String downPct, Instant from, Instant to, Instant fetchedAt) {}

  private final BoardProperties props;
  private final ExternalHttp http;
  private final ObjectMapper json;
  private final Clock clock;
  private volatile Snapshot last;
  private volatile int failures;
  private volatile String lastError;
  private volatile Instant openUntil;

  @org.springframework.beans.factory.annotation.Autowired
  BtcBoard(BoardProperties props, ObjectMapper json, Clock clock) {
    this(props, new ExternalHttp(), json, clock);
  }

  BtcBoard(BoardProperties props, ExternalHttp http, ObjectMapper json, Clock clock) {
    this.props = props;
    this.http = http;
    this.json = json;
    this.clock = clock;
  }

  /** 最近一次取到的数据；从来没取到过为 null */
  public Snapshot snapshot() {
    return last;
  }

  /** 取一次；成功返回 true。熔断中直接返回 false（不调外部接口） */
  public synchronized boolean refresh() {
    Instant now = clock.instant();
    if (openUntil != null && now.isBefore(openUntil)) return false;
    try {
      Snapshot s = fetch(now);
      last = s;
      failures = 0;
      lastError = null;
      openUntil = null;
      return true;
    } catch (RuntimeException | ExternalHttp.Failure e) {
      failures++;
      lastError = e.getMessage();
      if (failures >= props.getBreakerThreshold()) {
        openUntil = now.plus(props.getBreakerOpen());
        log.warn("数据页：币安行情连续 {} 次取不到，{} 内不再请求：{}", failures, props.getBreakerOpen(), lastError);
      } else {
        log.info("数据页：币安行情取不到（第 {} 次）：{}", failures, lastError);
      }
      return false;
    }
  }

  private Snapshot fetch(Instant now) throws ExternalHttp.Failure {
    int n = props.getHours();
    URI uri = URI.create(props.getBinanceUrl() + "/api/v3/klines?symbol=" + props.getSymbol() + "&interval=1h&limit=" + (n + 1));
    ExternalHttp.Response r = withRetry(uri);
    if (r.status() != 200) throw new IllegalStateException("币安返回 HTTP " + r.status());
    JsonNode arr = json.readTree(r.body());
    if (!arr.isArray() || arr.isEmpty()) throw new IllegalStateException("币安返回的不是 K 线数组");
    return compute(arr, n, now, props.getSymbol());
  }

  /** 按口径算（单独拿出来，测试用同一段 K 线手算对照，AC-96） */
  static Snapshot compute(JsonNode arr, int n, Instant now, String symbol) {
    List<JsonNode> closed = new ArrayList<>();
    JsonNode latest = null;
    for (JsonNode k : arr) {
      if (!k.isArray() || k.size() < 7) throw new IllegalStateException("K 线格式不对");
      latest = k;
      if (k.get(6).asLong() < now.toEpochMilli()) closed.add(k);
    }
    List<JsonNode> use = closed.subList(Math.max(0, closed.size() - n), closed.size());
    if (use.isEmpty()) throw new IllegalStateException("没有已经收盘的 K 线");
    int up = 0;
    for (JsonNode k : use) {
      BigDecimal open = new BigDecimal(k.get(1).asString());
      BigDecimal close = new BigDecimal(k.get(4).asString());
      if (close.compareTo(open) >= 0) up++;
    }
    int down = use.size() - up;
    BigDecimal total = BigDecimal.valueOf(use.size());
    String upPct = BigDecimal.valueOf(up * 100L).divide(total, 1, RoundingMode.HALF_UP).toPlainString();
    String downPct = BigDecimal.valueOf(down * 100L).divide(total, 1, RoundingMode.HALF_UP).toPlainString();
    String price = new BigDecimal(latest.get(4).asString()).setScale(2, RoundingMode.HALF_UP).toPlainString();
    return new Snapshot(symbol, price, use.size(), up, down, upPct, downPct, Instant.ofEpochMilli(use.get(0).get(0).asLong()),
        Instant.ofEpochMilli(use.get(use.size() - 1).get(6).asLong() + 1), now);
  }

  private ExternalHttp.Response withRetry(URI uri) throws ExternalHttp.Failure {
    int attempts = props.getRetries() + 1;
    for (int i = 1; ; i++) {
      try {
        ExternalHttp.Response r = http.get(uri, props.getConnectTimeout(), props.getReadTimeout(), MAX_BYTES, Map.of());
        boolean retryable = r.status() >= 500 || r.status() == 429 || r.status() == 408;
        if (!retryable || i >= attempts) return r;
      } catch (ExternalHttp.Failure e) {
        if (e.kind() == ExternalHttp.Kind.TOO_LARGE || i >= attempts) throw e;
      }
      sleep(300L * i);
    }
  }

  private static void sleep(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /** 只在定时刷新打开时检查（后端测试关闭刷新，不报）；连续 3 次刷新失败为 DOWN */
  @Override
  public List<Item> evaluate() {
    if (!props.isRefreshEnabled()) return List.of();
    boolean ok = failures < ALERT_AFTER;
    return List.of(new Item("board.binance", "数据页：币安行情取不到", "Data page: Binance prices unavailable", ok,
        ok ? "正常" : "连续 " + failures + " 次取不到：" + lastError));
  }
}
