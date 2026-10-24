package com.forecast.app.admin.ops;

import com.forecast.app.account.api.CustomerAdminApi;
import com.forecast.app.admin.security.AdminContext;
import com.forecast.app.admin.security.AdminNames;
import com.forecast.app.admin.security.Audit;
import com.forecast.app.common.error.BizException;
import com.forecast.app.common.error.ValidationException;
import com.forecast.app.ledger.api.AccountSnapshot;
import com.forecast.app.ledger.api.FundsApi;
import com.forecast.app.ledger.api.LedgerAdminApi;
import com.forecast.app.ledger.api.LedgerApi;
import com.forecast.app.ledger.api.LedgerEntryType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 后台"客户"：列表、详情、冻结 / 解冻、编辑资料、详情显示最近登录 IP（从复制源复制）。
 * <p>forecast S1：删掉了差价合约的持仓、已平仓记录和"调余额"（调余额改为结算补偿流程，S7，技术方案/v1-架构方案.md 第 1.2 节 admin）。
 */
@Service
class CustomerOps {

  /** 字段和原型 CustomerSummary 一致 */
  record Summary(long id, String name, String email, String nationality, String status, String kycStatus, String balance,
      String registeredAt, String lastLoginAt) {}

  record Kyc(String status, String docType, String docNumber, String reviewedAt, String rejectReason) {}

  /** S1：available = 可提金额（没有下注锁定）；S2 起加"下注锁定" */
  record Account(String balance, String available, String frozenWithdraw) {}

  record LedgerView(String id, String type, String amount, String balanceAfter, String ref, String time) {}

  /** 字段和原型 CustomerDetail 一致；lastLoginIp、lastLoginIpAt 是 v2 E6 加的（最近一个登录会话的 IP 和时间） */
  record Detail(long id, String name, String email, String nationality, String status, String kycStatus, String balance,
      String registeredAt, String lastLoginAt, String lastLoginIp, String lastLoginIpAt, String firstName, String middleName, String lastName,
      String birthDate, String frozenReason, Kyc kyc, Account account, List<LedgerView> ledger) {}

  /**
   * 编辑资料的请求体（v2 E1）。email、status 只用来拒绝：编辑里不能改邮箱，状态只能用冻结 / 解冻改。
   */
  record ProfileBody(String firstName, String middleName, String lastName, String birthDate, String nationality, String email, String status,
      String reason) {}

  private final CustomerAdminApi customers;
  private final LedgerAdminApi ledgerAdmin;
  private final LedgerApi ledger;
  private final FundsApi funds;
  private final Audit audit;

  CustomerOps(CustomerAdminApi customers, LedgerAdminApi ledgerAdmin, LedgerApi ledger, FundsApi funds, Audit audit) {
    this.customers = customers;
    this.ledgerAdmin = ledgerAdmin;
    this.ledger = ledger;
    this.funds = funds;
    this.audit = audit;
  }

  static String apiStatus(String s) {
    return s == null ? null : s.toLowerCase();
  }

  private static String dbStatus(String api) {
    return api == null || api.isBlank() ? null : api.strip().toUpperCase();
  }

  Summary summary(CustomerAdminApi.Customer c, BigDecimal balance) {
    return new Summary(c.id(), c.name(), c.email(), c.nationality(), apiStatus(c.status()), apiStatus(c.kycStatus()), Ops.money(balance == null ? BigDecimal.ZERO : balance),
        Ops.time(c.registeredAt()), Ops.time(c.lastLoginAt()));
  }

  @Transactional(readOnly = true)
  Ops.Page<Summary> list(String keyword, String status, String kycStatus, int page, int pageSize) {
    Ops.Slice s = Ops.slice(page, pageSize);
    var p = customers.customers(Ops.blank(keyword), dbStatus(status), dbStatus(kycStatus), s.offset(), s.size());
    List<Long> ids = p.items().stream().map(CustomerAdminApi.Customer::id).toList();
    Map<Long, BigDecimal> balances = ledgerAdmin.balances(ids);
    return new Ops.Page<>(p.items().stream().map(c -> summary(c, balances.get(c.id()))).toList(), p.total(), s.page(), s.size());
  }

  /** 发通知时按客户搜索：最多 20 个 */
  @Transactional(readOnly = true)
  List<Summary> search(String keyword) {
    return list(keyword, null, null, 1, 20).items();
  }

  @Transactional(readOnly = true)
  Detail detail(long id) {
    CustomerAdminApi.CustomerDetail d = customers.customer(id).orElseThrow(() -> new BizException("NOT_FOUND", "客户不存在"));
    CustomerAdminApi.Customer c = d.customer();
    AccountSnapshot a = ledger.snapshot(id);
    List<LedgerView> lines = ledgerAdmin.balanceLedger(id, 50).stream().map(l -> new LedgerView(String.valueOf(l.id()), ledgerType(l.type()), Ops.money(l.amount()),
        Ops.money(l.balanceAfter()), l.bizType() + ":" + l.bizId(), Ops.time(l.time()))).toList();
    Kyc kyc = new Kyc(apiStatus(c.kycStatus()), docType(d.docType()), d.docNumber(), Ops.time(d.kycReviewedAt()), d.kycRejectReason());
    Account account = new Account(Ops.money(a.balance()), Ops.money(funds.withdrawable(id)), Ops.money(a.withdrawFrozen()));
    LocalDate birth = d.birthDate();
    return new Detail(c.id(), c.name(), c.email(), c.nationality(), apiStatus(c.status()), apiStatus(c.kycStatus()), Ops.money(a.balance()),
        Ops.time(c.registeredAt()), Ops.time(c.lastLoginAt()), d.lastLoginIp(), Ops.time(d.lastLoginIpAt()), d.firstName(), d.middleName(), d.lastName(),
        birth == null ? null : birth.toString(), d.frozenReason(), kyc, account, lines);
  }

  /**
   * 编辑客户资料（v2 E1，高危，必须填原因）：姓名（名、中间名、姓）、出生日期、国籍。
   * 审计只记改了的字段；KYC 已通过的客户也允许改，但审计里标出 {@code kycApproved: true}（修改后和证件可能不一致），页面上也会提示。
   */
  @Transactional
  Detail editProfile(long id, ProfileBody b) {
    ProfileBody body = b == null ? new ProfileBody(null, null, null, null, null, null, null, null) : b;
    String r = Audit.requireReason(body.reason());
    ValidationException.Collector notEditable = new ValidationException.Collector();
    if (body.email() != null) notEditable.add("email", "notEditable");
    if (body.status() != null) notEditable.add("status", "notEditable");
    notEditable.throwIfAny();
    audit.run("customer.edit", String.valueOf(id), true, r, () -> {
      CustomerAdminApi.CustomerDetail d = customers.customer(id).orElseThrow(() -> new BizException("NOT_FOUND", "客户不存在"));
      CustomerAdminApi.ProfileEdit before = customers.updateProfile(id, new CustomerAdminApi.ProfileEdit(body.firstName(), body.middleName(), body.lastName(),
          body.birthDate(), body.nationality()));
      CustomerAdminApi.CustomerDetail now = customers.customer(id).orElseThrow();
      CustomerAdminApi.ProfileEdit after = new CustomerAdminApi.ProfileEdit(now.firstName(), now.middleName(), now.lastName(),
          now.birthDate() == null ? null : now.birthDate().toString(), now.customer().nationality());
      Map<String, Object> was = new java.util.LinkedHashMap<>();
      Map<String, Object> is = new java.util.LinkedHashMap<>();
      for (var comp : CustomerAdminApi.ProfileEdit.class.getRecordComponents()) {
        try {
          Object x = comp.getAccessor().invoke(before);
          Object y = comp.getAccessor().invoke(after);
          if (!java.util.Objects.equals(x == null ? "" : x, y == null ? "" : y)) {
            was.put(comp.getName(), x);
            is.put(comp.getName(), y);
          }
        } catch (ReflectiveOperationException e) {
          throw new IllegalStateException(e);
        }
      }
      if ("APPROVED".equals(d.customer().kycStatus())) {
        // KYC 已通过：允许改，但在审计里标出（改姓名、出生日期会和证件不一致）
        is.put("kycApproved", true);
        if (is.containsKey("firstName") || is.containsKey("middleName") || is.containsKey("lastName") || is.containsKey("birthDate")) {
          is.put("kycMismatch", "KYC 已通过，修改后和证件不一致");
        }
      }
      return Audit.Change.of(null, was, is);
    });
    return detail(id);
  }

  static String docType(String db) {
    return db == null ? null : "ID_CARD".equals(db) ? "id_card" : db.toLowerCase();
  }

  /** 账本类型 → 原型的流水类型；没有对应的用小写原名 */
  static String ledgerType(LedgerEntryType t) {
    return switch (t) {
      case DEPOSIT, DEPOSIT_REVERSAL -> "deposit";
      case WITHDRAW_FREEZE, WITHDRAW_PAYOUT -> "withdraw";
      case WITHDRAW_UNFREEZE -> "refund";
    };
  }

  /** 冻结（必须填原因，客户所有登录立即失效，AC-04）或解冻 */
  @Transactional
  void setFrozen(long id, boolean frozen, String reason) {
    String r = Audit.requireReason(reason);
    audit.run(frozen ? "customer.freeze" : "customer.unfreeze", String.valueOf(id), true, r, () -> {
      CustomerAdminApi.CustomerDetail d = customers.customer(id).orElseThrow(() -> new BizException("NOT_FOUND", "客户不存在"));
      customers.setFrozen(id, frozen, frozen ? r : null);
      return Audit.Change.of(null, Map.of("status", apiStatus(d.customer().status())), Map.of("status", frozen ? "frozen" : "active"));
    });
  }
}
