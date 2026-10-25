package com.forecast.app.resolve.platform;

import com.forecast.app.market.api.MarketApi;
import com.forecast.app.market.api.MarketStatus;
import com.forecast.app.pool.api.PoolApi;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 客户端市场详情里平台裁决的公开信息（{@code resolution} 字段，forecast S7；需求第 4.4 节"已结算市场详情页公开：结果、依据、裁决时间、裁决人编号"、
 * 第 5.2 节 P2"裁决与公示（结果、依据、裁决人编号、争议截止时间、争议结论）"、AC-34）。<b>不显示争议人</b>，也不显示管理员姓名（只有编号）。
 * 结构（客户端 S11 照着接）：
 * <pre>
 * { "mode": "platform", "voided": false,
 *   "resultOptionNo": "…",            ← 最终结果（改判后是新结果；作废为空）
 *   "publicizedOptionNo": "…",        ← 公示的拟定结果（改判时和最终结果不同）
 *   "basis": "NORMAL" | "E6_COMPLETED" | "E7_OFFICIAL" | "E11_USUAL" | 作废的依据，
 *   "evidenceUrls": ["https://…"], "explanation": "理由", "actualStartAt": "…Z" | null,
 *   "resolvedBy": "管理员编号" | "SYSTEM", "resolvedAt": "…Z",   ← 改判的是处理争议的管理员和时间
 *   "disputeDeadlineAt": "…Z", "disputeCount": 2,
 *   "dispute": { "decision": "upheld" | "overturned" | "voided", "conclusion": "…", "decidedAt": "…Z" } | null,
 *   "myDispute": { "disputeNo", "claimedOptionNo", "claimVoid", "status", "receivedAt" } | null,   ← 只有当前客户自己的
 *   "canDispute": true | false }
 * </pre>
 * 没有平台裁决的确认 / 作废记录时返回 null（自动裁决的由 {@code ResolutionViews} 给）。
 */
@Component
public class PlatformViews {

  private final PlatformMapper mapper;
  private final MarketApi markets;
  private final PoolApi pool;
  private final Clock clock;
  private final ObjectMapper json;

  PlatformViews(PlatformMapper mapper, MarketApi markets, PoolApi pool, Clock clock, ObjectMapper json) {
    this.mapper = mapper;
    this.markets = markets;
    this.pool = pool;
    this.clock = clock;
    this.json = json;
  }

  public Map<String, Object> clientView(long marketId, MarketStatus status, Long userId) {
    List<PlatformRows.Record> records = mapper.records(marketId);
    PlatformRows.Record confirm = last(records, Set.of(ResolveDesk.CONFIRM));
    PlatformRows.Record voided = last(records, Set.of(ResolveDesk.VOID));
    PlatformRows.Record decision = last(records, Set.of(ResolveDesk.DISPUTE_UPHOLD, ResolveDesk.DISPUTE_OVERTURN, ResolveDesk.DISPUTE_VOID));
    if (confirm == null && voided == null) return null;
    MarketApi.PlatformRef m = markets.platformRef(marketId).orElse(null);
    if (m == null) return null;
    Map<Long, String> nos = new LinkedHashMap<>();
    for (MarketApi.OptionRef o : m.options()) nos.put(o.optionId(), String.valueOf(o.optionNo()));
    PlatformRows.Case c = mapper.findCase(marketId);
    boolean isVoided = status == MarketStatus.VOIDED;
    // 依据：确认的那一条（作废的是作废记录；人工作废没有确认时也用作废记录）
    PlatformRows.Record basisRec = isVoided && voided != null ? voided : confirm != null ? confirm : voided;
    Map<String, Object> d = detail(basisRec);
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("mode", "platform");
    out.put("voided", isVoided);
    Long finalOption = isVoided ? null : decision != null && ResolveDesk.DISPUTE_OVERTURN.equals(decision.kind) ? decision.optionId
        : confirm == null ? null : confirm.optionId;
    out.put("resultOptionNo", finalOption == null ? null : nos.get(finalOption));
    out.put("publicizedOptionNo", confirm == null || confirm.optionId == null ? null : nos.get(confirm.optionId));
    out.put("basis", d.get("basis"));
    out.put("evidenceUrls", list(basisRec.evidenceUrls));
    out.put("explanation", basisRec.note);
    out.put("actualStartAt", basisRec.actualStartAt == null ? null : iso(basisRec.actualStartAt));
    boolean overturned = decision != null && ResolveDesk.DISPUTE_OVERTURN.equals(decision.kind);
    PlatformRows.Record by = isVoided && decision != null && ResolveDesk.DISPUTE_VOID.equals(decision.kind) ? decision
        : isVoided && voided != null ? voided : overturned ? decision : confirm;
    out.put("resolvedBy", by == null ? null : by.actor);
    out.put("resolvedAt", by == null ? null : iso(by.createdAt));
    out.put("disputeDeadlineAt", c == null || c.disputeDeadlineAt == null ? null : iso(c.disputeDeadlineAt));
    out.put("disputeCount", mapper.countDisputes(marketId));
    if (decision != null) {
      Map<String, Object> dv = new LinkedHashMap<>();
      dv.put("decision", switch (decision.kind) {
        case ResolveDesk.DISPUTE_UPHOLD -> "upheld";
        case ResolveDesk.DISPUTE_OVERTURN -> "overturned";
        default -> "voided";
      });
      dv.put("conclusion", decision.note);
      dv.put("decidedAt", iso(decision.createdAt));
      out.put("dispute", dv);
    } else {
      out.put("dispute", null);
    }
    PlatformRows.Dispute mine = userId == null ? null : mapper.disputeOfUser(marketId, userId);
    if (mine != null) {
      Map<String, Object> mv = new LinkedHashMap<>();
      mv.put("disputeNo", String.valueOf(mine.publicNo));
      mv.put("claimedOptionNo", mine.claimedOptionId == null ? null : nos.get(mine.claimedOptionId));
      mv.put("claimVoid", mine.claimedOptionId == null);
      mv.put("status", mine.status.toLowerCase());
      mv.put("receivedAt", iso(mine.receivedAt));
      out.put("myDispute", mv);
    } else {
      out.put("myDispute", null);
    }
    LocalDateTime now = LocalDateTime.now(clock.withZone(ZoneOffset.UTC));
    boolean can = userId != null && mine == null && (status == MarketStatus.PUBLICIZING || status == MarketStatus.DISPUTED) && c != null && c.decision == null
        && c.disputeDeadlineAt != null && now.isBefore(c.disputeDeadlineAt) && pool.hasPosition(userId, marketId);
    out.put("canDispute", can);
    return out;
  }

  private static PlatformRows.Record last(List<PlatformRows.Record> records, Set<String> kinds) {
    PlatformRows.Record out = null;
    for (PlatformRows.Record r : records) if (kinds.contains(r.kind)) out = r;
    return out;
  }

  private Map<String, Object> detail(PlatformRows.Record r) {
    if (r == null || r.detail == null || r.detail.isBlank()) return Map.of();
    Map<String, Object> d = json.readValue(r.detail, new TypeReference<Map<String, Object>>() {});
    return d == null ? Map.of() : d;
  }

  private List<String> list(String s) {
    if (s == null || s.isBlank()) return new ArrayList<>();
    List<String> l = json.readValue(s, new TypeReference<List<String>>() {});
    return l == null ? new ArrayList<>() : l;
  }

  private static String iso(LocalDateTime t) {
    return t.toInstant(ZoneOffset.UTC).toString();
  }
}
