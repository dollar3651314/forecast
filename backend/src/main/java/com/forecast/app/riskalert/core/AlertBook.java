package com.forecast.app.riskalert.core;

import com.forecast.app.common.error.BizException;
import com.forecast.app.market.api.MarketApi;
import com.forecast.app.notification.api.StaffAlertApi;
import com.forecast.app.riskalert.api.RiskAlertAdminApi;
import com.forecast.app.riskalert.api.RiskAlertApi;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 告警的记录和查询（forecast S10）：{@link #raise} 记一条（同一情况只记一次）并在同一事务里给员工发邮件（模板 {@code RISK_ALERT}，
 * 收件人是有 {@code risk_alert:view} 的在职管理员，没有人时发告警邮箱，项目地图约定 51）；后台列表、改状态；提现自动审批 R14、R15 的查询。
 */
@Service
public class AlertBook implements RiskAlertApi, RiskAlertAdminApi {

  private static final Logger log = LoggerFactory.getLogger(AlertBook.class);
  private static final TypeReference<Map<String, String>> MAP = new TypeReference<>() {};
  /** R15 看的告警类型（需求第 8.2 节："内幕交易""尾盘大额"） */
  static final Set<String> SUSPICIOUS = Set.of("NEW_ACCOUNT_LONGSHOT", "LATE_LARGE_BET");
  /** 告警类型在邮件里的名字（员工邮件只有中英文） */
  static final Map<String, String[]> KIND_NAMES = Map.of(
      "LATE_LARGE_BET", new String[] {"尾盘大额", "Large late bet"},
      "LINKED_OPPOSITE", new String[] {"关联账户对押", "Linked accounts on opposite options"},
      "NEW_ACCOUNT_LONGSHOT", new String[] {"新账户押中冷门", "New account won a long shot"},
      "DEPOSIT_NO_BET", new String[] {"充了不玩就提", "Withdrawal after depositing without betting"});

  private final RiskMapper mapper;
  private final MarketApi markets;
  private final StaffAlertApi staff;
  private final com.forecast.app.ledger.api.CustomerFlowApi flows;
  private final com.forecast.app.pool.api.PoolApi pool;
  private final ObjectMapper json;
  private final Clock clock;

  AlertBook(RiskMapper mapper, MarketApi markets, StaffAlertApi staff, com.forecast.app.ledger.api.CustomerFlowApi flows, com.forecast.app.pool.api.PoolApi pool,
      ObjectMapper json, Clock clock) {
    this.mapper = mapper;
    this.markets = markets;
    this.staff = staff;
    this.flows = flows;
    this.pool = pool;
    this.json = json;
    this.clock = clock;
  }

  LocalDateTime now() {
    return LocalDateTime.now(clock.withZone(ZoneOffset.UTC));
  }

  /**
   * 记一条告警（在调用方的事务里）。同一类型、同一客户、同一 ref 已经有了就什么都不做。
   *
   * @param refKey {@code M:市场 id} 或 {@code W:提现 id}
   * @param summary 邮件里的一句说明 [中文, 英文]
   * @return 是不是新记的
   */
  boolean raise(String kind, long userId, Long marketId, String refKey, BigDecimal amount, Map<String, String> detail, String[] summary) {
    RiskMapper.AlertRow r = new RiskMapper.AlertRow();
    r.kind = kind;
    r.userId = userId;
    r.marketId = marketId;
    r.refKey = refKey;
    r.amount = amount;
    r.detail = json.writeValueAsString(detail);
    r.createdAt = now();
    if (mapper.insertAlert(r) != 1) return false;
    Map<String, String> v = new LinkedHashMap<>();
    String[] names = KIND_NAMES.get(kind);
    v.put("kind", names[0]);
    v.put("kindEn", names[1]);
    v.put("userId", String.valueOf(userId));
    v.put("market", marketId == null ? "—" : markets.findById(marketId).map(m -> m.publicNo() + "（" + m.title().zhCN() + "）").orElse(String.valueOf(marketId)));
    v.put("amount", amount == null ? "—" : money(amount));
    v.put("summary", summary[0]);
    v.put("summaryEn", summary[1]);
    staff.notifyStaff("RISK_ALERT", v);
    log.info("风控告警 {}：客户 {}，{}，{}", kind, userId, refKey, summary[0]);
    return true;
  }

  static String money(BigDecimal v) {
    return v.setScale(2, RoundingMode.DOWN).toPlainString();
  }

  // ---------- RiskAlertApi（提现自动审批） ----------

  @Override
  @Transactional(readOnly = true)
  public LinkedOpposite linkedOpposite(long userId, LocalDateTime since) {
    List<Long> linked = mapper.linkedUsers(userId);
    if (linked.isEmpty()) return new LinkedOpposite(BigDecimal.ZERO, List.of(), List.of());
    BigDecimal profit = BigDecimal.ZERO;
    List<Long> marketIds = new ArrayList<>();
    java.util.TreeSet<Long> users = new java.util.TreeSet<>();
    for (var m : flows.paidMarketsSince(userId, since)) {
      List<Long> all = new ArrayList<>(linked);
      all.add(userId);
      Map<Long, Set<Long>> opts = pool.optionsOf(m.marketId(), all);
      Set<Long> mine = opts.getOrDefault(userId, Set.of());
      boolean hit = false;
      for (Long l : linked) {
        Set<Long> theirs = opts.get(l);
        if (theirs != null && opposite(mine, theirs)) {
          hit = true;
          users.add(l);
        }
      }
      if (hit) {
        marketIds.add(m.marketId());
        profit = profit.add(m.realized().net());
      }
    }
    return new LinkedOpposite(profit, List.copyOf(marketIds), List.copyOf(users));
  }

  /** 两个人押了不同的选项：存在 x ∈ a、y ∈ b，x ≠ y（结算后持仓仍是有效下注，所以已结算的市场也能看出来） */
  static boolean opposite(Set<Long> a, Set<Long> b) {
    if (a.isEmpty() || b.isEmpty()) return false;
    for (Long x : a) for (Long y : b) if (!x.equals(y)) return true;
    return false;
  }

  @Override
  @Transactional(readOnly = true)
  public long openSuspiciousAlerts(long userId, LocalDateTime since) {
    return mapper.openAlertsOf(userId, SUSPICIOUS, since);
  }

  // ---------- RiskAlertAdminApi ----------

  private Alert alert(RiskMapper.AlertRow r) {
    Map<String, String> d;
    try {
      d = r.detail == null ? Map.of() : json.readValue(r.detail, MAP);
    } catch (RuntimeException e) {
      d = Map.of();
    }
    return new Alert(r.id, r.kind, r.userId, r.marketId, r.amount, d, r.status, r.note, r.handledBy, r.handledAt, r.createdAt);
  }

  @Override
  @Transactional(readOnly = true)
  public Page alerts(String kind, String status, Collection<Long> userIds, int offset, int limit) {
    if (userIds != null && userIds.isEmpty()) return new Page(List.of(), 0);
    return new Page(mapper.alerts(kind, status, userIds, offset, limit).stream().map(this::alert).toList(), mapper.countAlerts(kind, status, userIds));
  }

  @Override
  @Transactional(readOnly = true)
  public Map<String, Map<String, Long>> counts() {
    Map<String, Map<String, Long>> out = new LinkedHashMap<>();
    for (String k : KINDS) {
      Map<String, Long> m = new LinkedHashMap<>();
      for (String s : STATUSES) m.put(s, 0L);
      out.put(k, m);
    }
    for (RiskMapper.CountRow c : mapper.countByKindStatus()) {
      Map<String, Long> m = out.get(c.kind);
      if (m != null && m.containsKey(c.status)) m.put(c.status, c.n);
    }
    return out;
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<Alert> alert(long id) {
    return Optional.ofNullable(mapper.alert(id)).map(this::alert);
  }

  @Override
  @Transactional
  public Alert setStatus(long id, String status, String note, long adminId) {
    if (!STATUSES.contains(status)) throw new BizException("INVALID_STATUS", "状态不对：" + status);
    RiskMapper.AlertRow r = mapper.lockAlert(id);
    if (r == null) throw new BizException("NOT_FOUND", "告警不存在");
    Alert before = alert(r);
    if (status.equals(r.status) && java.util.Objects.equals(note, r.note)) return before;
    if (mapper.setStatus(id, r.status, status, note, adminId, now()) != 1) throw new IllegalStateException("告警状态被别人改了：" + id);
    return before;
  }

  @Override
  @Transactional(readOnly = true)
  public List<Link> linksOf(long userId) {
    return mapper.linksOf(userId).stream().map(l -> new Link(l.other, l.reason, l.evidence, l.firstSeenAt)).toList();
  }
}
