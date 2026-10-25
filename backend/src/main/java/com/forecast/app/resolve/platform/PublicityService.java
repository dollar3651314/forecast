package com.forecast.app.resolve.platform;

import com.forecast.app.market.api.MarketApi;
import com.forecast.app.market.api.MarketStatus;
import com.forecast.app.market.api.SettlePhase;
import com.forecast.app.notification.api.StaffAlertApi;
import com.forecast.app.resolve.api.ResultHandler;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * 平台裁决的定时部分（forecast S7；技术方案/v1-架构方案.md 第 5.2 节 PublicityJob、ResolveTimeoutJob；{@link PlatformJob} 调用，测试和端到端也直接调用）。
 * <ul>
 *   <li>{@link #settleDue}：<b>公示期满、没有争议 → 结算</b>（AC-31，第 12 条边）；争议结论是维持 / 改判、但结算还没交出去的（处理时进程退出等）→ 结算（第 14 条边）。
 *       结算要求市场"正好在公示中 / 争议中"（{@link ResultHandler#settle(long, long, java.time.Instant, String, MarketStatus)}），在市场行锁上和提交争议排队；
 *       结算本身三道防重复，重复触发只结算一次。交出去以后记 {@code settle_requested_at}，不再重复交。</li>
 *   <li>{@link #holdsDue}：E6 延期、E7 腰斩等满 48 小时还没解除 → 系统作废、全额退款（第 15 条边）。</li>
 *   <li>{@link #remindOverdue}：待提议、待确认、争议处理超时 → 邮件提醒（同一项只发一次；裁决台另外标红，AC-36）。收件人按 SQ-14 的口径发到告警邮箱，按角色发在 S9。</li>
 * </ul>
 * 都是按"条件已满足"扫描（停机恢复后第一轮补上），每一步都是条件更新，多实例、租约意外失效时重复执行没有效果。
 */
@Service
public class PublicityService {

  private static final Logger log = LoggerFactory.getLogger(PublicityService.class);
  static final int BATCH = 50;

  private final PlatformMapper mapper;
  private final MarketApi markets;
  private final ObjectProvider<ResultHandler> handler;
  private final StaffAlertApi staff;
  private final Clock clock;
  private final ObjectMapper json;
  private final TransactionTemplate tx;

  PublicityService(PlatformMapper mapper, MarketApi markets, ObjectProvider<ResultHandler> handler, StaffAlertApi staff, Clock clock, ObjectMapper json,
      PlatformTransactionManager tm) {
    this.mapper = mapper;
    this.markets = markets;
    this.handler = handler;
    this.staff = staff;
    this.clock = clock;
    this.json = json;
    this.tx = new TransactionTemplate(tm);
    this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  LocalDateTime now() {
    return LocalDateTime.now(clock.withZone(ZoneOffset.UTC)).truncatedTo(ChronoUnit.MILLIS);
  }

  /**
   * 跑一轮：该交给结算的逐个交（不在事务里；结算自己开事务）。
   *
   * @return 市场 id → settled（已结算 / 已开始结算）/ voided（结算时 E1 作废）/ skipped（条件不满足，例如刚有人提交争议）/ error
   */
  public Map<Long, String> settleDue() {
    Map<Long, String> out = new LinkedHashMap<>();
    for (long id : mapper.settleDue(now(), BATCH)) {
      try {
        PlatformRows.Case c = mapper.findCase(id);
        if (c == null || c.settleRequestedAt != null) continue;
        MarketStatus expect = "PUBLICIZING".equals(c.stage) ? MarketStatus.PUBLICIZING : MarketStatus.DISPUTED;
        out.put(id, requestSettle(id, expect));
      } catch (RuntimeException e) {
        log.error("市场 {} 交给结算失败，下一轮重试", id, e);
        out.put(id, "error");
      }
    }
    return out;
  }

  /**
   * 交给结算（公示期满、争议处理完；{@code ResolveDesk.decideDisputes} 处理完立即调用一次）。条件不满足时什么都不做：
   * 公示中要求争议截止时间已到；争议中要求结论是维持 / 改判。
   *
   * @return settled / voided / skipped / error
   */
  public String requestSettle(long marketId, MarketStatus expect) {
    PlatformRows.Case c = mapper.findCase(marketId);
    MarketApi.PlatformRef m = markets.platformRef(marketId).orElse(null);
    if (c == null || m == null || c.resultOptionId == null) return "skipped";
    LocalDateTime now = now();
    if (expect == MarketStatus.PUBLICIZING) {
      if (m.status() != MarketStatus.PUBLICIZING || c.disputeDeadlineAt == null || c.disputeDeadlineAt.isAfter(now)) return "skipped";
    } else if (expect != MarketStatus.DISPUTED || m.status() != MarketStatus.DISPUTED || c.decision == null || "VOID".equals(c.decision)) {
      return "skipped";
    }
    ResultHandler h = handler.getIfAvailable();
    if (h == null) {
      log.error("市场 {} 要结算，但没有结算模块（ResultHandler）", m.publicNo());
      return "error";
    }
    try {
      h.settle(marketId, c.resultOptionId, c.actualStartAt == null ? null : c.actualStartAt.toInstant(ZoneOffset.UTC), c.resolvedBy, expect);
    } catch (RuntimeException e) {
      log.error("市场 {} 结算失败，下一轮重试", m.publicNo(), e);
      return "error";
    }
    MarketApi.PlatformRef after = markets.platformRef(marketId).orElse(m);
    boolean started = after.settlePhase() != SettlePhase.NONE || after.status().terminal();
    if (!started) return "skipped"; // 例如结算拿到市场行锁时已经有人提交了争议（公示中 → 争议中）
    tx.executeWithoutResult(st -> {
      if (mapper.markSettleRequested(marketId, now()) == 1 && expect == MarketStatus.PUBLICIZING) {
        PlatformRows.Record r = new PlatformRows.Record();
        r.marketId = marketId;
        r.kind = ResolveDesk.PUBLICITY_END;
        r.optionId = c.resultOptionId;
        r.refId = c.confirmRecordId;
        r.actor = ResolveDesk.SYSTEM;
        r.createdAt = now();
        mapper.insertRecord(r);
      }
    });
    log.info("市场 {} {}，已交给结算：{}", m.publicNo(), expect == MarketStatus.PUBLICIZING ? "公示期满、没有争议" : "争议处理完", after.status().api());
    return after.status() == MarketStatus.VOIDED ? "voided" : "settled";
  }

  /** E6 / E7 等满 48 小时还没解除的：系统作废（每个市场一个事务） */
  public Map<Long, String> holdsDue() {
    Map<Long, String> out = new LinkedHashMap<>();
    for (long id : mapper.holdsDue(now(), BATCH)) {
      try {
        out.put(id, Boolean.TRUE.equals(tx.execute(st -> expireHold(id))) ? "voided" : "skipped");
      } catch (RuntimeException e) {
        log.error("市场 {} 延期 / 腰斩到期作废失败，下一轮重试", id, e);
        out.put(id, "error");
      }
    }
    return out;
  }

  private boolean expireHold(long marketId) {
    LocalDateTime now = now();
    MarketApi.PlatformRef m = markets.lockPlatform(marketId);
    if (m.status() != MarketStatus.PENDING_RESOLUTION || m.settlePhase() != SettlePhase.NONE) return false;
    PlatformRows.Case c = mapper.lockCase(marketId);
    if (c == null || c.holdKind == null || c.holdUntil.isAfter(now)) return false;
    boolean postponed = "POSTPONED".equals(c.holdKind);
    PlatformRows.Record r = new PlatformRows.Record();
    r.marketId = marketId;
    r.kind = ResolveDesk.VOID;
    r.actor = ResolveDesk.SYSTEM;
    r.detail = json.writeValueAsString(Map.of("outcome", "void", "basis", postponed ? "E6_TIMEOUT" : "E7_NO_RESULT", "hold", c.holdKind));
    r.createdAt = now;
    mapper.insertRecord(r);
    boolean ok = markets.transition(marketId, MarketStatus.PENDING_RESOLUTION, MarketStatus.VOIDED, ResolveDesk.SYSTEM, postponed ? "POSTPONED_48H" : "ABANDONED_48H");
    if (ok) log.info("市场 {} {}等满 48 小时没有解除，作废、全额退款", m.publicNo(), postponed ? "延期" : "腰斩");
    return ok;
  }

  /**
   * 超时提醒（AC-36）：待提议、待确认、争议处理超过时限的，各发一次邮件（先条件更新"已提醒"，更新到 1 行才发：多实例只发一次）。
   *
   * @return 这一轮发了几封
   */
  public int remindOverdue() {
    int n = 0;
    LocalDateTime now = now();
    for (String kind : List.of("PROPOSE", "CONFIRM", "DISPUTE")) {
      for (PlatformRows.Case c : mapper.overdue(kind, now, BATCH)) {
        if (mapper.markAlerted(c.marketId, kind, now) != 1) continue;
        MarketApi.PlatformRef m = markets.platformRef(c.marketId).orElse(null);
        if (m == null) continue;
        LocalDateTime due = switch (kind) {
          case "PROPOSE" -> c.proposeDueAt;
          case "CONFIRM" -> c.confirmDueAt;
          default -> c.disputeDueAt;
        };
        Map<String, String> v = new LinkedHashMap<>();
        v.put("marketNo", String.valueOf(m.publicNo()));
        v.put("market", m.title().zhCN());
        v.put("marketEn", m.title().en());
        v.put("what", kind);
        v.put("dueAt", ResolveDesk.NOTICE_TIME.format(due));
        try {
          staff.notifyStaff("RESOLVE_OVERDUE", v);
          n++;
        } catch (RuntimeException e) {
          log.error("市场 {} 的超时提醒发送失败", m.publicNo(), e);
        }
      }
    }
    return n;
  }
}
