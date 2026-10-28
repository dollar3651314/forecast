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
import com.forecast.app.ledger.api.TestCreditApi;
import com.forecast.app.settle.api.SettleAdminApi;
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
      String registeredAt, String lastLoginAt, boolean staff) {}

  record Kyc(String status, String docType, String docNumber, String reviewedAt, String rejectReason) {}

  /** 账户（S2）：余额 = 可用 + 下注锁定；available 是可提金额（= 可用余额）；betLocked 下注锁定；frozenWithdraw 提现冻结 */
  record Account(String balance, String available, String betLocked, String frozenWithdraw) {}

  record LedgerView(String id, String type, String amount, String balanceAfter, String ref, String time) {}

  /** 字段和原型 CustomerDetail 一致；lastLoginIp、lastLoginIpAt 是 v2 E6 加的（最近一个登录会话的 IP 和时间） */
  record Detail(long id, String name, String email, String nationality, String status, String kycStatus, String balance,
      String registeredAt, String lastLoginAt, String lastLoginIp, String lastLoginIpAt, String firstName, String middleName, String lastName,
      String birthDate, String frozenReason, Kyc kyc, Account account, List<LedgerView> ledger, boolean staff, TestCreditInfo testCredit,
      String adjustMax) {}

  /**
   * 测试入金（forecast S17A）：只在测试环境（FC_ENV_LABEL=test）有值，后台据此显示"测试入金"按钮；生产是 null，按钮不显示。
   *
   * @param maxAmount 单笔上限（参数设置 adjust.testCreditMax）
   * 另：Detail.adjustMax 是人工调账的单笔上限（S17B，参数 adjust.manualMax；超过时确认框里要再勾一次）
   */
  record TestCreditInfo(String maxAmount) {}

  /** 测试入金的请求体：金额、原因、请求号（后台每次打开确认框生成）、勾选确认 */
  record TestCreditBody(String amount, String reason, String requestId, Boolean checked) {}

  /** 一笔测试入金：duplicate = 这个请求号之前已经入过，这次没有再入 */
  record TestCreditView(String creditNo, String amount, String availableBefore, String availableAfter, boolean duplicate) {}

  record TestCreditResult(Detail customer, TestCreditView credit) {}

  /** 登记 / 取消登记员工账户（forecast S10）：{"staff": true, "reason": "…"} */
  record StaffBody(Boolean staff, String reason) {}

  /** 登记的结果：客户详情 + 退回的下注（E15，只有登记时有） */
  record StaffResult(Detail customer, SettleAdminApi.StaffRefund refunded) {}

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
  private final SettleAdminApi settle;
  private final TestCreditApi testCredit;
  private final com.forecast.app.ledger.api.ManualAdjustApi manualAdjust;

  CustomerOps(CustomerAdminApi customers, LedgerAdminApi ledgerAdmin, LedgerApi ledger, FundsApi funds, Audit audit, SettleAdminApi settle, TestCreditApi testCredit,
      com.forecast.app.ledger.api.ManualAdjustApi manualAdjust) {
    this.manualAdjust = manualAdjust;
    this.testCredit = testCredit;
    this.settle = settle;
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
        Ops.time(c.registeredAt()), Ops.time(c.lastLoginAt()), c.staff());
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
    Account account = new Account(Ops.money(a.balance()), Ops.money(funds.withdrawable(id)), Ops.money(a.betLocked()), Ops.money(a.withdrawFrozen()));
    LocalDate birth = d.birthDate();
    return new Detail(c.id(), c.name(), c.email(), c.nationality(), apiStatus(c.status()), apiStatus(c.kycStatus()), Ops.money(a.balance()),
        Ops.time(c.registeredAt()), Ops.time(c.lastLoginAt()), d.lastLoginIp(), Ops.time(d.lastLoginIpAt()), d.firstName(), d.middleName(), d.lastName(),
        birth == null ? null : birth.toString(), d.frozenReason(), kyc, account, lines, c.staff(),
        testCredit.enabled() ? new TestCreditInfo(Ops.money(testCredit.maxAmount())) : null, Ops.money(manualAdjust.maxAmount()));
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

  /**
   * 账本类型 → 后台的流水类型（web/admin 的 ledger.* 翻译）。这里只会出现可用余额一项的记录（{@link LedgerAdminApi#balanceLedger}）；
   * 彩池的类型（S2 定义，S4 起才有记录）先给出名字，平台账户的类型不会出现在客户流水里。
   */
  static String ledgerType(LedgerEntryType t) {
    return switch (t) {
      case DEPOSIT, DEPOSIT_REVERSAL -> "deposit";
      case WITHDRAW_FREEZE, WITHDRAW_PAYOUT -> "withdraw";
      case WITHDRAW_UNFREEZE -> "refund";
      case BET_LOCK, BET_SETTLE, BET_LOST -> "bet";
      case PAYOUT -> "payout";
      case FEE -> "fee";
      case REFUND -> "betRefund";
      case COMPENSATION -> "compensation";
      case TEST_CREDIT -> "testCredit";
      case MANUAL_CREDIT -> "adjustCredit";
      case MANUAL_DEBIT -> "adjustDebit";
      case FEE_INCOME, SETTLE_DUST, COMPENSATION_EXPENSE, TEST_CREDIT_EXPENSE, MANUAL_ADJUST_OUT, MANUAL_ADJUST_IN -> t.name().toLowerCase();
    };
  }

  /**
   * 登记 / 取消登记员工账户（forecast S10，需求第 3.3、8.1 节；高危，原因必填，写审计"前 → 后"）。登记后不能下注；
   * 漏登记事后补登的，按 E15 当场退回已有下注（结算还没开始的市场，{@code SettleAdminApi.refundStaffBets}），退回的笔数和金额写进审计。
   * 取消登记只改标记，已经退回的下注不恢复。不加 @Transactional：标记先提交，退回每个市场一个事务（和下注、结算的加锁顺序一致）。
   */
  StaffResult setStaff(long id, StaffBody body) {
    String r = Audit.requireReason(body == null ? null : body.reason());
    if (body.staff() == null) throw new ValidationException(java.util.List.of(new ValidationException.Field("staff", "required")));
    boolean staff = body.staff();
    SettleAdminApi.StaffRefund refunded = audit.run(staff ? "customer.staff" : "customer.unstaff", String.valueOf(id), true, r, () -> {
      boolean before = customers.setStaff(id, staff);
      SettleAdminApi.StaffRefund out = staff ? settle.refundStaffBets(id) : new SettleAdminApi.StaffRefund(0, 0, "0.00");
      Map<String, Object> is = new java.util.LinkedHashMap<>();
      is.put("staff", staff);
      if (staff) {
        is.put("refundedMarkets", out.markets());
        is.put("refundedBets", out.bets());
        is.put("refundedAmount", out.amount());
      }
      return Audit.Change.of(out, Map.of("staff", before), is);
    });
    return new StaffResult(detail(id), refunded);
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

  /**
   * 测试入金（forecast S17A；高危：原因必填、勾选确认、写审计，记下入金前后的可用余额）。接口只在测试环境注册（{@link TestCreditAdminController}），
   * 这里和账本里再各挡一次（不是测试环境 404）。同一个请求号重复提交只入一次，审计照样记一条（duplicate = true）。
   */
  TestCreditResult testCredit(AdminContext actor, long id, TestCreditBody b) {
    if (!testCredit.enabled()) throw new BizException("NOT_FOUND", "测试入金只在测试环境可用");
    TestCreditBody body = b == null ? new TestCreditBody(null, null, null, null) : b;
    String r = Audit.requireReason(body.reason());
    ValidationException.Collector bad = new ValidationException.Collector();
    if (!Boolean.TRUE.equals(body.checked())) bad.add("checked", "required");
    BigDecimal amount = parseAmount(body.amount(), bad);
    bad.throwIfAny();
    customers.customer(id).orElseThrow(() -> new BizException("NOT_FOUND", "客户不存在"));
    TestCreditView view = audit.run("customer.test_credit", String.valueOf(id), true, r, () -> {
      TestCreditApi.Result res = testCredit.credit(id, amount, r, body.requestId(), actor.adminId());
      Map<String, Object> before = new java.util.LinkedHashMap<>();
      before.put("available", Ops.money(res.availableBefore()));
      Map<String, Object> after = new java.util.LinkedHashMap<>();
      after.put("available", Ops.money(res.availableAfter()));
      after.put("amount", Ops.money(res.amount()));
      after.put("creditNo", String.valueOf(res.creditNo()));
      after.put("requestId", body.requestId());
      after.put("duplicate", res.duplicate());
      return Audit.Change.of(new TestCreditView(String.valueOf(res.creditNo()), Ops.money(res.amount()), Ops.money(res.availableBefore()),
          Ops.money(res.availableAfter()), res.duplicate()), before, after);
    });
    return new TestCreditResult(detail(id), view);
  }

  /** 金额：正数、最多 8 位小数（不合法记字段错误，返回 null） */
  static BigDecimal parseAmount(String v, ValidationException.Collector bad) {
    if (v == null || v.isBlank()) {
      bad.add("amount", "required");
      return null;
    }
    try {
      BigDecimal d = new BigDecimal(v.strip());
      if (d.signum() <= 0 || d.stripTrailingZeros().scale() > 8 || d.compareTo(new BigDecimal("1000000000")) > 0) {
        bad.add("amount", "invalid");
        return null;
      }
      return d;
    } catch (NumberFormatException e) {
      bad.add("amount", "invalid");
      return null;
    }
  }
}
