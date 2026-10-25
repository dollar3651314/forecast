package com.forecast.app.resolve.auto;

import com.forecast.app.market.api.MarketResolutionView;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 客户端市场详情里的 {@code resolution} 字段（market 的 {@link MarketResolutionView}；forecast S6，需求第 4.3 节"已结算市场详情页公开显示开始价、结束价和来源"、AC-27）。
 * 结构（客户端 S11 照着接；价格是字符串，原样不舍入）：
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
  private final ObjectMapper json;

  ResolutionViews(ResolveMapper mapper, ObjectMapper json) {
    this.mapper = mapper;
    this.json = json;
  }

  @Override
  public Object clientResolution(long marketId) {
    ResolveRows.Record r = mapper.latestRecord(marketId, CryptoResolver.KIND_RESULT);
    if (r == null || r.detail == null) return null;
    Map<String, Object> d = json.readValue(r.detail, new TypeReference<Map<String, Object>>() {});
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

  private static String lower(Object o) {
    return o == null ? null : String.valueOf(o).toLowerCase();
  }
}
