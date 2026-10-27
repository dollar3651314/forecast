package com.forecast.app.admin.ops;

import com.forecast.app.account.api.CustomerAdminApi;
import com.forecast.app.admin.security.AdminContext;
import com.forecast.app.admin.security.AdminNames;
import com.forecast.app.admin.security.Audit;
import com.forecast.app.common.error.BizException;
import com.forecast.app.common.error.ValidationException;
import com.forecast.app.ledger.api.ManualAdjustApi;
import com.forecast.app.ledger.api.ManualAdjustApi.Category;
import com.forecast.app.ledger.api.ManualAdjustApi.Direction;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * 后台人工调账（forecast S17B，需求说明书 v1.5 第 21 节）：客户详情的"人工加款""人工扣款"（{@code balance:adjust}，高危）、
 * "资金 → 人工调账记录"列表和 CSV 导出（{@code recon:view}，导出写审计）。钱的规则在 {@code ledger.api.ManualAdjustApi}。
 */
@Service
class AdjustOps {

  /**
   * 调整的请求体。direction：credit / debit；category：deposit_missing / wrong_credit / other；refNo 选填；
   * checked 必须是 true（高危确认）；超过单笔上限时 overLimitConfirmed 必须是 true。
   */
  record AdjustBody(String direction, String category, String amount, String refNo, String reason, String requestId, Boolean checked,
      Boolean overLimitConfirmed) {}

  /** 一笔调整（列表和结果共用） */
  record AdjustView(String adjustNo, long customerId, String customerEmail, String customerName, String direction, String category, String amount,
      String availableBefore, String availableAfter, String refNo, String reason, boolean overLimit, String operator, String createdAt) {}

  /** 调整的结果：客户详情 + 这一笔；duplicate = 同一个请求号之前已经调过 */
  record AdjustResult(CustomerOps.Detail customer, AdjustView adjustment, boolean duplicate) {}

  static final int EXPORT_MAX = 10_000;

  private final ManualAdjustApi adjust;
  private final CustomerAdminApi customers;
  private final CustomerOps customerOps;
  private final AdminNames names;
  private final Audit audit;

  AdjustOps(ManualAdjustApi adjust, CustomerAdminApi customers, CustomerOps customerOps, AdminNames names, Audit audit) {
    this.adjust = adjust;
    this.customers = customers;
    this.customerOps = customerOps;
    this.names = names;
    this.audit = audit;
  }

  static Direction direction(String v, ValidationException.Collector bad, boolean required) {
    if (v == null || v.isBlank()) {
      if (required) bad.add("direction", "required");
      return null;
    }
    try {
      return Direction.valueOf(v.strip().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      bad.add("direction", "invalid");
      return null;
    }
  }

  static Category category(String v, ValidationException.Collector bad, boolean required) {
    if (v == null || v.isBlank()) {
      if (required) bad.add("category", "required");
      return null;
    }
    try {
      return Category.valueOf(v.strip().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      bad.add("category", "invalid");
      return null;
    }
  }

  /**
   * 人工加款 / 扣款（高危：原因必填、勾选确认、写审计 {@code customer.adjust}，记调整前后的可用余额、方向、类别、金额、参考号、是否超过上限）。
   */
  AdjustResult adjust(AdminContext actor, long id, AdjustBody b) {
    AdjustBody body = b == null ? new AdjustBody(null, null, null, null, null, null, null, null) : b;
    String r = Audit.requireReason(body.reason());
    ValidationException.Collector bad = new ValidationException.Collector();
    if (!Boolean.TRUE.equals(body.checked())) bad.add("checked", "required");
    Direction d = direction(body.direction(), bad, true);
    Category c = category(body.category(), bad, true);
    if (d != null && c != null && !c.allows(d)) bad.add("category", "invalid");
    BigDecimal amount = CustomerOps.parseAmount(body.amount(), bad);
    if (body.refNo() != null && body.refNo().strip().length() > 128) bad.add("refNo", "tooLong");
    bad.throwIfAny();
    customers.customer(id).orElseThrow(() -> new BizException("NOT_FOUND", "客户不存在"));
    String action = d == Direction.CREDIT ? "customer.adjust_credit" : "customer.adjust_debit";
    ManualAdjustApi.Adjustment a = audit.run(action, String.valueOf(id), true, r, () -> {
      ManualAdjustApi.Adjustment res = adjust.adjust(new ManualAdjustApi.Request(id, d, c, amount, body.refNo(), r, body.requestId(),
          Boolean.TRUE.equals(body.overLimitConfirmed()), actor.adminId()));
      Map<String, Object> before = new LinkedHashMap<>();
      before.put("available", Ops.money(res.availableBefore()));
      Map<String, Object> after = new LinkedHashMap<>();
      after.put("available", Ops.money(res.availableAfter()));
      after.put("direction", api(res.direction()));
      after.put("category", api(res.category()));
      after.put("amount", Ops.money(res.amount()));
      after.put("refNo", res.refNo());
      after.put("overLimit", res.overLimit());
      after.put("adjustNo", String.valueOf(res.adjustNo()));
      after.put("requestId", body.requestId());
      after.put("duplicate", res.duplicate());
      return Audit.Change.of(res, before, after);
    });
    return new AdjustResult(customerOps.detail(id), views(List.of(a)).get(0), a.duplicate());
  }

  static String api(Enum<?> e) {
    return e.name().toLowerCase(Locale.ROOT);
  }

  private List<AdjustView> views(List<ManualAdjustApi.Adjustment> list) {
    if (list.isEmpty()) return List.of();
    Map<Long, CustomerAdminApi.Customer> cs = customers.customers(list.stream().map(ManualAdjustApi.Adjustment::userId).distinct().toList());
    Map<Long, String> admins = names.of(list.stream().map(ManualAdjustApi.Adjustment::createdBy).distinct().toList());
    List<AdjustView> out = new ArrayList<>();
    for (ManualAdjustApi.Adjustment a : list) {
      CustomerAdminApi.Customer c = cs.get(a.userId());
      out.add(new AdjustView(String.valueOf(a.adjustNo()), a.userId(), c == null ? null : c.email(), c == null ? null : c.name(), api(a.direction()),
          api(a.category()), Ops.money(a.amount()), Ops.money(a.availableBefore()), Ops.money(a.availableAfter()), a.refNo(), a.reason(), a.overLimit(),
          admins.getOrDefault(a.createdBy(), String.valueOf(a.createdBy())), Ops.time(a.createdAt())));
    }
    return out;
  }

  /** 筛选：customerId（客户编号）、direction、category、from / to（UTC 日期，含两头） */
  ManualAdjustApi.Query query(String customerId, String direction, String category, String from, String to) {
    ValidationException.Collector bad = new ValidationException.Collector();
    Long uid = null;
    if (customerId != null && !customerId.isBlank()) {
      try {
        uid = Long.valueOf(customerId.strip());
      } catch (NumberFormatException e) {
        bad.add("customerId", "invalid");
      }
    }
    Direction d = direction(direction, bad, false);
    Category c = category(category, bad, false);
    LocalDate f = date(from, "from", bad);
    LocalDate t = date(to, "to", bad);
    bad.throwIfAny();
    return new ManualAdjustApi.Query(uid, d, c, f == null ? null : f.atStartOfDay(), t == null ? null : t.plusDays(1).atStartOfDay());
  }

  private static LocalDate date(String v, String field, ValidationException.Collector bad) {
    if (v == null || v.isBlank()) return null;
    try {
      return LocalDate.parse(v.strip());
    } catch (DateTimeParseException e) {
      bad.add(field, "invalid");
      return null;
    }
  }

  Ops.Page<AdjustView> list(String customerId, String direction, String category, String from, String to, int page, int pageSize) {
    ManualAdjustApi.Query q = query(customerId, direction, category, from, to);
    Ops.Slice s = Ops.slice(page, pageSize);
    return new Ops.Page<>(views(adjust.list(q, s.offset(), s.size())), adjust.count(q), s.page(), s.size());
  }

  /** CSV（UTF-8 带 BOM；最多 1 万行），写审计 {@code adjust.export}（筛选条件和行数） */
  String export(String customerId, String direction, String category, String from, String to, String lang) {
    ManualAdjustApi.Query q = query(customerId, direction, category, from, to);
    boolean en = "en".equalsIgnoreCase(lang);
    Map<String, Object> filter = new LinkedHashMap<>();
    filter.put("customerId", customerId);
    filter.put("direction", direction);
    filter.put("category", category);
    filter.put("from", from);
    filter.put("to", to);
    return audit.run("adjust.export", "-", false, null, () -> {
      List<AdjustView> rows = views(adjust.list(q, 0, EXPORT_MAX));
      StringBuilder sb = new StringBuilder("﻿");
      line(sb, en ? List.of("Adjustment no", "Time (UTC)", "Customer ID", "Customer email", "Direction", "Category", "Amount (USDT)", "Available before",
          "Available after", "Reference", "Reason", "Over limit", "Operator")
          : List.of("调账编号", "时间（UTC）", "客户编号", "客户邮箱", "方向", "类别", "金额（USDT）", "调整前可用", "调整后可用", "参考号", "原因", "超过单笔上限", "操作人"));
      for (AdjustView v : rows) {
        line(sb, List.of(v.adjustNo(), Objects.toString(v.createdAt(), ""), String.valueOf(v.customerId()), Objects.toString(v.customerEmail(), ""),
            dirText(v.direction(), en), catText(v.category(), en), v.amount(), v.availableBefore(), v.availableAfter(), Objects.toString(v.refNo(), ""),
            v.reason(), v.overLimit() ? (en ? "yes" : "是") : (en ? "no" : "否"), v.operator()));
      }
      Map<String, Object> after = new LinkedHashMap<>(filter);
      after.put("rows", rows.size());
      return Audit.Change.of(sb.toString(), null, after);
    });
  }

  static String dirText(String d, boolean en) {
    return "credit".equals(d) ? (en ? "Credit" : "人工加款") : (en ? "Debit" : "人工扣款");
  }

  static String catText(String c, boolean en) {
    return switch (c) {
      case "deposit_missing" -> en ? "Missing deposit credited" : "充值未到账补入";
      case "wrong_credit" -> en ? "Erroneous credit reversed" : "误入账扣回";
      default -> en ? "Other" : "其他";
    };
  }

  /** 一行 CSV：含逗号、引号、换行的加引号；以 = + - @ 开头的前面加单引号（防止表格软件当公式执行，和来源统计的导出同一个做法） */
  static void line(StringBuilder sb, List<String> cells) {
    for (int i = 0; i < cells.size(); i++) {
      if (i > 0) sb.append(',');
      String s = Objects.toString(cells.get(i), "");
      if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) s = "'" + s;
      if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) s = "\"" + s.replace("\"", "\"\"") + "\"";
      sb.append(s);
    }
    sb.append("\r\n");
  }
}
