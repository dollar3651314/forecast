package com.forecast.app.support;

import com.forecast.app.support.StubHttp.Reply;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 替身数据源的"K 线簿"（forecast S6 测试用）：按来源、币种、窗口开始设开始价和结束价，或者设成某种故障；{@link #handler()} 照币安、OKX、Coinbase 真实接口的格式回复
 * （路径前缀 /binance、/okx、/coinbase，配置里把三个来源的地址指到 StubHttp 的这三个前缀）。没设的窗口返回空数据（"还不全"）。
 */
public final class CandleBook {

  /** 一个来源对一个窗口怎么回：价格，或者故障 */
  public record Entry(String open, String close, Reply fault, int confirmLast) {}

  private final Map<String, Entry> entries = new ConcurrentHashMap<>();
  /** 某个来源全部请求都这样回（优先于价格），例如一直 500 */
  private final Map<String, Reply> sourceFault = new ConcurrentHashMap<>();

  public void clear() {
    entries.clear();
    sourceFault.clear();
  }

  public void price(String source, String symbol, Instant windowStart, String open, String close) {
    entries.put(key(source, symbol, windowStart.toEpochMilli()), new Entry(open, close, null, 1));
  }

  /** OKX：最后一根还没收盘 */
  public void unconfirmed(String symbol, Instant windowStart, String open, String close) {
    entries.put(key("OKX", symbol, windowStart.toEpochMilli()), new Entry(open, close, null, 0));
  }

  public void fault(String source, Reply r) {
    if (r == null) sourceFault.remove(source);
    else sourceFault.put(source, r);
  }

  public Function<URI, Reply> handler() {
    return u -> {
      String path = u.getPath();
      Map<String, String> q = query(u);
      if (path.startsWith("/binance/")) {
        Reply f = sourceFault.get("BINANCE");
        if (f != null) return f;
        long ws = Long.parseLong(q.get("startTime"));
        long we = Long.parseLong(q.get("endTime")) + 1;
        Entry e = entries.get(key("BINANCE", q.get("symbol"), ws));
        if (e == null) return Reply.ok("[]");
        List<String> rows = new ArrayList<>();
        for (long t = ws; t < we; t += 60_000) {
          String o = t == ws ? e.open() : e.open();
          String c = t + 60_000 >= we ? e.close() : e.open();
          rows.add("[" + t + ",\"" + o + "\",\"" + o + "\",\"" + o + "\",\"" + c + "\",\"1.0\"," + (t + 59_999) + ",\"1\",1,\"0.5\",\"0.5\",\"0\"]");
        }
        return Reply.ok("[" + String.join(",", rows) + "]");
      }
      if (path.startsWith("/okx/")) {
        Reply f = sourceFault.get("OKX");
        if (f != null) return f;
        long we = Long.parseLong(q.get("after"));
        long ws = Long.parseLong(q.get("before")) + 1;
        String symbol = q.get("instId").replace("-", "");
        Entry e = entries.get(key("OKX", symbol, ws));
        if (e == null) return Reply.ok("{\"code\":\"0\",\"msg\":\"\",\"data\":[]}");
        List<String> rows = new ArrayList<>();
        for (long t = we - 60_000; t >= ws; t -= 60_000) {
          String c = t + 60_000 >= we ? e.close() : e.open();
          String confirm = t + 60_000 >= we ? String.valueOf(e.confirmLast()) : "1";
          rows.add("[\"" + t + "\",\"" + e.open() + "\",\"" + e.open() + "\",\"" + e.open() + "\",\"" + c + "\",\"1\",\"1\",\"1\",\"" + confirm + "\"]");
        }
        return Reply.ok("{\"code\":\"0\",\"msg\":\"\",\"data\":[" + String.join(",", rows) + "]}");
      }
      if (path.startsWith("/coinbase/")) {
        Reply f = sourceFault.get("COINBASE");
        if (f != null) return f;
        long ws = Long.parseLong(q.get("start")) * 1000;
        long lastStart = Long.parseLong(q.get("end")) * 1000;
        String asset = path.replaceAll(".*/products/([A-Z]+)-USD/candles", "$1");
        Entry e = entries.get(key("COINBASE", asset + "USDT", ws));
        if (e == null) return Reply.ok("{\"candles\":[]}");
        List<String> rows = new ArrayList<>();
        for (long t = lastStart; t >= ws; t -= 60_000) {
          String c = t == lastStart ? e.close() : e.open();
          rows.add("{\"start\":\"" + t / 1000 + "\",\"low\":\"" + e.open() + "\",\"high\":\"" + e.open() + "\",\"open\":\"" + e.open() + "\",\"close\":\"" + c
              + "\",\"volume\":\"1\"}");
        }
        return Reply.ok("{\"candles\":[" + String.join(",", rows) + "]}");
      }
      return Reply.status(404, "{}");
    };
  }

  private static String key(String source, String symbol, long start) {
    return source + ":" + symbol + ":" + start;
  }

  private static Map<String, String> query(URI u) {
    Map<String, String> out = new HashMap<>();
    if (u.getRawQuery() == null) return out;
    for (String kv : u.getRawQuery().split("&")) {
      int i = kv.indexOf('=');
      if (i > 0) out.put(kv.substring(0, i), kv.substring(i + 1));
    }
    return out;
  }
}
