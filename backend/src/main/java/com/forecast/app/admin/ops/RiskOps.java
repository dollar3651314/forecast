package com.forecast.app.admin.ops;

import com.forecast.app.account.api.CustomerAdminApi;
import com.forecast.app.admin.security.AdminContext;
import com.forecast.app.admin.security.AdminNames;
import com.forecast.app.admin.security.Audit;
import com.forecast.app.common.error.BizException;
import com.forecast.app.common.error.ValidationException;
import com.forecast.app.market.api.MarketApi;
import com.forecast.app.riskalert.api.RiskAlertAdminApi;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Service;

/**
 * 后台"风控告警"（forecast S10，需求说明书第 6.2 节）：列表（类型、状态、客户筛选）、各类数量、改处理状态（已查看 / 已处理 / 误报，写审计）；
 * 客户详情的关联账户。告警本身由 riskalert 模块扫描生成。
 */
@Service
class RiskOps {

  /**
   * 一条告警（后台显示）。kind、status 小写（late_large_bet / new / seen / handled / false_positive）；金额字符串（同后台其他接口，Ops.money），编号都是字符串。
   *
   * @param detail 判定时的数字（键见 riskalert.core.RiskRules）
   */
  record AlertView(String id, String kind, String userId, String customer, String marketNo, String market, String amount, Map<String, String> detail,
      String status, String note, String handledBy, String handledAt, String createdAt) {}

  /** 关联账户的一行：对方客户、原因（device / ip / deposit_from / withdraw_to）、共同的值、发现时间 */
  record LinkView(String userId, String customer, String reason, String evidence, String firstSeenAt) {}

  /** 改状态：status = seen / handled / false_positive / new；已处理、误报要写处理说明 */
  record StatusBody(String status, String note) {}

  private final RiskAlertAdminApi risk;
  private final CustomerAdminApi customers;
  private final MarketApi markets;
  private final AdminNames names;
  private final Audit audit;

  RiskOps(RiskAlertAdminApi risk, CustomerAdminApi customers, MarketApi markets, AdminNames names, Audit audit) {
    this.risk = risk;
    this.customers = customers;
    this.markets = markets;
    this.names = names;
    this.audit = audit;
  }

  private static String up(String v, List<String> allowed, String field) {
    String s = Ops.blank(v);
    if (s == null) return null;
    String u = s.toUpperCase(Locale.ROOT);
    if (!allowed.contains(u)) throw new ValidationException(List.of(new ValidationException.Field(field, "invalid")));
    return u;
  }

  Ops.Page<AlertView> list(String kind, String status, String keyword, int page, int pageSize) {
    Ops.Slice s = Ops.slice(page, pageSize);
    String k = up(kind, RiskAlertAdminApi.KINDS, "kind");
    String st = up(status, RiskAlertAdminApi.STATUSES, "status");
    String kw = Ops.blank(keyword);
    List<Long> users = kw == null ? null : customers.search(kw, 200);
    RiskAlertAdminApi.Page p = risk.alerts(k, st, users, s.offset(), s.size());
    return new Ops.Page<>(views(p.items()), p.total(), s.page(), s.size());
  }

  private List<AlertView> views(List<RiskAlertAdminApi.Alert> items) {
    Set<Long> userIds = new TreeSet<>();
    Set<Long> marketIds = new TreeSet<>();
    Set<Long> admins = new TreeSet<>();
    for (var a : items) {
      userIds.add(a.userId());
      if (a.marketId() != null) marketIds.add(a.marketId());
      if (a.handledBy() != null) admins.add(a.handledBy());
    }
    Map<Long, CustomerAdminApi.Customer> cs = customers.customers(userIds);
    Map<Long, MarketApi.MarketRef> ms = marketIds.isEmpty() ? Map.of() : markets.refs(marketIds);
    Map<Long, String> an = admins.isEmpty() ? Map.of() : names.of(admins);
    List<AlertView> out = new ArrayList<>();
    for (var a : items) {
      CustomerAdminApi.Customer c = cs.get(a.userId());
      MarketApi.MarketRef m = a.marketId() == null ? null : ms.get(a.marketId());
      out.add(new AlertView(String.valueOf(a.id()), a.kind().toLowerCase(Locale.ROOT), String.valueOf(a.userId()), c == null ? null : c.name() == null ? c.email() : c.name(),
          m == null ? null : String.valueOf(m.publicNo()), m == null ? null : m.title().zhCN(), a.amount() == null ? null : Ops.money(a.amount()), a.detail(),
          a.status().toLowerCase(Locale.ROOT), a.note(), a.handledBy() == null ? null : an.getOrDefault(a.handledBy(), String.valueOf(a.handledBy())),
          Ops.time(a.handledAt()), Ops.time(a.createdAt())));
    }
    return out;
  }

  /** 各类型待处理（新 + 已查看）的条数和合计，菜单角标和页面的分组用 */
  Map<String, Object> counts() {
    Map<String, Object> out = new LinkedHashMap<>();
    long open = 0;
    Map<String, Long> byKind = new LinkedHashMap<>();
    Map<String, Long> byStatus = new LinkedHashMap<>();
    for (String st : RiskAlertAdminApi.STATUSES) byStatus.put(st.toLowerCase(Locale.ROOT), 0L);
    for (var e : risk.counts().entrySet()) {
      long n = e.getValue().get("NEW") + e.getValue().get("SEEN");
      byKind.put(e.getKey().toLowerCase(Locale.ROOT), n);
      open += n;
      e.getValue().forEach((st, v) -> byStatus.merge(st.toLowerCase(Locale.ROOT), v, Long::sum));
    }
    out.put("open", open);
    out.put("openByKind", byKind);
    out.put("byStatus", byStatus);
    return out;
  }

  AlertView setStatus(AdminContext actor, long id, StatusBody body) {
    String to = up(body == null ? null : body.status(), RiskAlertAdminApi.STATUSES, "status");
    if (to == null) throw new ValidationException(List.of(new ValidationException.Field("status", "required")));
    String note = body.note() == null || body.note().isBlank() ? null : body.note().strip();
    if (note != null && note.length() > 500) throw new ValidationException(List.of(new ValidationException.Field("note", "tooLong")));
    if (note == null && ("HANDLED".equals(to) || "FALSE_POSITIVE".equals(to))) {
      throw new ValidationException(List.of(new ValidationException.Field("note", "required")));
    }
    audit.run("risk_alert.status", String.valueOf(id), false, note, () -> {
      RiskAlertAdminApi.Alert before = risk.setStatus(id, to, note, actor.adminId());
      Map<String, Object> was = new LinkedHashMap<>();
      was.put("status", before.status());
      was.put("note", before.note());
      Map<String, Object> is = new LinkedHashMap<>();
      is.put("status", to);
      is.put("note", note);
      return Audit.Change.of(null, was, is);
    });
    return views(List.of(risk.alert(id).orElseThrow(() -> new BizException("NOT_FOUND", "告警不存在")))).getFirst();
  }

  List<LinkView> links(long userId) {
    List<RiskAlertAdminApi.Link> ls = risk.linksOf(userId);
    Map<Long, CustomerAdminApi.Customer> cs = customers.customers(ls.stream().map(RiskAlertAdminApi.Link::userId).distinct().toList());
    return ls.stream().map(l -> {
      CustomerAdminApi.Customer c = cs.get(l.userId());
      return new LinkView(String.valueOf(l.userId()), c == null ? null : Objects.requireNonNullElse(c.name(), c.email()), l.reason().toLowerCase(Locale.ROOT),
          l.evidence(), Ops.time(l.firstSeenAt()));
    }).toList();
  }
}
