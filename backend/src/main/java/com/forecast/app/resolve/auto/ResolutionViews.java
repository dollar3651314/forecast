package com.forecast.app.resolve.auto;

import com.forecast.app.market.api.MarketResolutionView;
import com.forecast.app.market.api.MarketStatus;
import com.forecast.app.resolve.platform.PlatformViews;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 客户端市场详情里的 {@code resolution} 字段（market 的 {@link MarketResolutionView}）。
 * <ul>
 *   <li>forecast S7：有平台裁决的确认 / 作废记录的（平台裁决的市场、自动裁决转人工后由人确认的）→ {@link PlatformViews}（结果、依据、裁决人编号、争议截止时间、
 *       争议结论，{@code mode = "platform"}）。</li>
 *   <li>forecast S6：已结算的自动裁决市场 → 开始价、结束价和来源（需求第 4.3 节、AC-27），结构见下。</li>
 * </ul>
 * 自动裁决的结构（客户端 S11 照着接；价格是字符串，原样不舍入）：
 * <pre>
 * { "mode": "auto", "result": "up" | "down", "resolvedBy": "AUTO", "resolvedAt": "…Z",
 *   "symbol": "BTCUSDT", "windowStart": "…Z", "windowEnd": "…Z",
 *   "startPrice": "…", "endPrice": "…", "source": "Binance BTC/USDT",          ← 按首选来源（拿不到时按实际用的来源）
 *   "sources": [ { "source": "BINANCE", "label": "Binance BTC/USDT", "startPrice", "endPrice", "direction": "up", "sha256" }, … ] }   ← 参与判定的来源
 * </pre>
 */
@Component
class ResolutionViews implements MarketResolutionView {

  private final ResolveMapper mapper;
  private final PlatformViews platform;
  private final ObjectMapper json;

  ResolutionViews(ResolveMapper mapper, PlatformViews platform, ObjectMapper json) {
    this.mapper = mapper;
    this.platform = platform;
    this.json = json;
  }

  @Override
  public Object clientResolution(long marketId, MarketStatus status, Long userId) {
    Map<String, Object> p = platform.clientView(marketId, status, userId);
    if (p != null) return p;
    return status == MarketStatus.SETTLED ? autoResolution(marketId) : null;
  }

  private Object autoResolution(long marketId) {
    ResolveRows.Record r = mapper.latestRecord(marketId, CryptoResolver.KIND_RESULT);
    if (r == null || r.detail == null) return null;
    Map<String, Object> d = json.readValue(r.detail, new TypeReference<Map<String, Object>>() {});
    if (d.get("sport") != null) return sportsResolution(r, d);
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("mode", "auto");
    out.put("result", lower(d.get("result")));
    out.put("resolvedBy", CryptoResolver.AUTO);
    out.put("resolvedAt", r.createdAt.toInstant(ZoneOffset.UTC).toString());
    out.put("symbol", d.get("symbol"));
    out.put("windowStart", d.get("windowStart"));
    out.put("windowEnd", d.get("windowEnd"));
    List<Map<String, Object>> used = new ArrayList<>();
    Map<String, Object> basis = null;
    Object sources = d.get("sources");
    if (sources instanceof List<?> list) {
      for (Object o : list) {
        if (!(o instanceof Map<?, ?> s) || !Boolean.TRUE.equals(s.get("used"))) continue;
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("source", s.get("source"));
        v.put("label", s.get("label"));
        v.put("startPrice", s.get("startPrice"));
        v.put("endPrice", s.get("endPrice"));
        v.put("direction", lower(s.get("direction")));
        v.put("sha256", s.get("sha256"));
        used.add(v);
        if (basis == null && String.valueOf(s.get("source")).equals(String.valueOf(d.get("basis")))) basis = v;
      }
    }
    if (basis == null && !used.isEmpty()) basis = used.get(0);
    out.put("startPrice", basis == null ? null : basis.get("startPrice"));
    out.put("endPrice", basis == null ? null : basis.get("endPrice"));
    out.put("source", basis == null ? null : basis.get("label"));
    out.put("sources", used);
    return out;
  }

  /**
   * 体育自动裁决（forecast S8）：结果、比分、实际开赛时间、来源（按首选来源）、参与判定的两个来源的比分和 sha256。
   * <pre>
   * { "mode": "auto", "result": "home" | "draw" | "away" | "over" | "under", "resolvedBy": "AUTO", "resolvedAt": "…Z",
   *   "sport": "FOOTBALL", "play": "FOOTBALL_OU", "line": "2.5", "home": "…", "away": "…", "homeScore": 2, "awayScore": 1, "actualStartAt": "…Z",
   *   "source": "football-data.org", "sources": [ { "source": "FOOTBALL_DATA", "label", "homeScore", "awayScore", "status": "finished", "sha256" }, … ] }
   * </pre>
   * 备源的主客队和主源相反时，sources 里的比分是备源原样的（{@code swapped = true}）。
   */
  private Object sportsResolution(ResolveRows.Record r, Map<String, Object> d) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("mode", "auto");
    out.put("result", lower(d.get("result")));
    out.put("resolvedBy", CryptoResolver.AUTO);
    out.put("resolvedAt", r.createdAt.toInstant(ZoneOffset.UTC).toString());
    for (String k : List.of("sport", "play", "line", "home", "away", "homeScore", "awayScore", "actualStartAt")) out.put(k, d.get(k));
    List<Map<String, Object>> used = new ArrayList<>();
    String basisLabel = null;
    if (d.get("sources") instanceof List<?> list) {
      for (Object o : list) {
        if (!(o instanceof Map<?, ?> s) || !Boolean.TRUE.equals(s.get("used"))) continue;
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("source", s.get("source"));
        v.put("label", s.get("label"));
        v.put("homeScore", s.get("homeScore"));
        v.put("awayScore", s.get("awayScore"));
        v.put("swapped", s.get("swapped"));
        v.put("status", lower(s.get("status")));
        v.put("sha256", s.get("sha256"));
        used.add(v);
        if (basisLabel == null && String.valueOf(s.get("source")).equals(String.valueOf(d.get("basis")))) basisLabel = String.valueOf(s.get("label"));
      }
    }
    out.put("source", basisLabel != null ? basisLabel : used.isEmpty() ? null : used.get(0).get("label"));
    out.put("sources", used);
    return out;
  }

  private static String lower(Object o) {
    return o == null ? null : String.valueOf(o).toLowerCase();
  }
}
