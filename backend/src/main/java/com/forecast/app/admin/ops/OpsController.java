package com.forecast.app.admin.ops;

import com.forecast.app.admin.security.AdminContext;
import com.forecast.app.admin.security.AdminLoginOnly;
import com.forecast.app.admin.security.Permission;
import com.forecast.app.admin.security.RequiresPermission;
import java.util.List;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后台业务接口：客户、KYC、资金（入金、出金、对账、任务）、通知（从复制源复制）。字段和 {@code web/admin/src/api/types.ts} 一致。
 * 每个接口都声明了权限点。forecast S1 删掉了"调余额"（改为结算补偿流程，S7）。
 */
@RestController
class OpsController {

  record FreezeBody(Boolean frozen, String reason) {}

  record ReasonBody(String reason) {}

  record ReviewBody(Boolean approve, String reason) {}

  record NoteBody(String note) {}

  private final DashboardOps dashboard;
  private final CustomerOps customers;
  private final FundsOps funds;
  private final NotifyOps notify;
  private final AutoApproveOps autoApprove;

  OpsController(DashboardOps dashboard, CustomerOps customers, FundsOps funds, NotifyOps notify, AutoApproveOps autoApprove) {
    this.dashboard = dashboard;
    this.customers = customers;
    this.funds = funds;
    this.notify = notify;
    this.autoApprove = autoApprove;
  }

  /** 菜单角标：待审核的 KYC、出金（没有查看权限的记 0） */
  @AdminLoginOnly
  @GetMapping("/api/admin/menu-badges")
  Map<String, Long> badges() {
    return funds.badges(AdminContext.current());
  }

  // ---------- 仪表盘（复制源在 MonitorController，交易监控删除后搬到这里） ----------

  @RequiresPermission(Permission.DASHBOARD_VIEW)
  @GetMapping("/api/admin/dashboard")
  DashboardOps.Stats dashboard() {
    return dashboard.stats();
  }

  // ---------- 客户 ----------

  @RequiresPermission(Permission.CUSTOMER_VIEW)
  @GetMapping("/api/admin/customers")
  Ops.Page<CustomerOps.Summary> customers(@RequestParam(required = false) String keyword, @RequestParam(required = false) String status,
      @RequestParam(required = false) String kycStatus, @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize) {
    return customers.list(keyword, status, kycStatus, page, pageSize);
  }

  @RequiresPermission(Permission.CUSTOMER_VIEW)
  @GetMapping("/api/admin/customers/{id}")
  CustomerOps.Detail customer(@PathVariable long id) {
    return customers.detail(id);
  }

  @RequiresPermission(Permission.CUSTOMER_FREEZE)
  @PostMapping("/api/admin/customers/{id}/freeze")
  CustomerOps.Detail freeze(@PathVariable long id, @RequestBody FreezeBody body) {
    customers.setFrozen(id, body != null && Boolean.TRUE.equals(body.frozen()), body == null ? null : body.reason());
    return customers.detail(id);
  }

  /** v2 E1：编辑客户资料（高危，必须填原因）；邮箱、状态不能在这里改 */
  @RequiresPermission(Permission.CUSTOMER_EDIT)
  @PutMapping("/api/admin/customers/{id}/profile")
  CustomerOps.Detail editProfile(@PathVariable long id, @RequestBody CustomerOps.ProfileBody body) {
    return customers.editProfile(id, body);
  }

  /** 发通知时选客户 */
  @RequiresPermission(Permission.NOTIFY_SEND)
  @GetMapping("/api/admin/customers/search")
  List<CustomerOps.Summary> searchCustomers(@RequestParam(required = false) String keyword) {
    return customers.search(keyword);
  }

  // ---------- KYC ----------

  @RequiresPermission(Permission.KYC_VIEW)
  @GetMapping("/api/admin/kyc")
  Ops.Page<FundsOps.KycView> kycs(@RequestParam(required = false) String status, @RequestParam(required = false) String keyword,
      @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize) {
    return funds.kycs(status, keyword, page, pageSize);
  }

  @RequiresPermission(Permission.KYC_VIEW)
  @GetMapping("/api/admin/kyc/{id}")
  FundsOps.KycView kyc(@PathVariable long id) {
    return funds.kyc(id);
  }

  /** 证件照片（front / back / selfie），不允许缓存 */
  @RequiresPermission(Permission.KYC_VIEW)
  @GetMapping("/api/admin/kyc/{id}/images/{side}")
  ResponseEntity<byte[]> kycImage(@PathVariable long id, @PathVariable String side) {
    var img = funds.kycImage(id, side);
    return ResponseEntity.ok().contentType(MediaType.parseMediaType(img.contentType())).cacheControl(CacheControl.noStore()).body(img.content());
  }

  @RequiresPermission(Permission.KYC_REVIEW)
  @PostMapping("/api/admin/kyc/{id}/review")
  FundsOps.KycView reviewKyc(@PathVariable long id, @RequestBody ReviewBody body) {
    return funds.reviewKyc(AdminContext.current(), id, body != null && Boolean.TRUE.equals(body.approve()), body == null ? null : body.reason());
  }

  // ---------- 资金 ----------

  @RequiresPermission(Permission.DEPOSIT_VIEW)
  @GetMapping("/api/admin/deposits")
  Ops.Page<FundsOps.DepositView> deposits(@RequestParam(required = false) String chain, @RequestParam(required = false) String status,
      @RequestParam(required = false) String keyword, @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize) {
    return funds.deposits(chain, status, keyword, page, pageSize);
  }

  @RequiresPermission(Permission.WITHDRAWAL_VIEW)
  @GetMapping("/api/admin/withdrawals")
  Ops.Page<FundsOps.WithdrawalView> withdrawals(@RequestParam(required = false) String status, @RequestParam(required = false) String chain,
      @RequestParam(required = false) String minAmount, @RequestParam(required = false) String maxAmount, @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String autoReview, @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize) {
    return funds.withdrawals(status, chain, minAmount, maxAmount, keyword, autoReview, page, pageSize);
  }

  // ---------- v2 提现自动审批规则（技术方案/v2-提现自动审批.md 第 7 节） ----------

  @RequiresPermission(Permission.WITHDRAWAL_VIEW)
  @GetMapping("/api/admin/withdraw-auto-approve")
  AutoApproveOps.SettingsView autoApproveSettings() {
    return autoApprove.settings();
  }

  /** 一次保存总开关和 13 条规则（高危）：必须填原因，expectedVersion 不对返回 409 CONFIG_CHANGED */
  @RequiresPermission(Permission.WITHDRAWAL_AUTO_RULE_EDIT)
  @PutMapping("/api/admin/withdraw-auto-approve")
  AutoApproveOps.SettingsView saveAutoApprove(@RequestBody AutoApproveOps.SaveBody body) {
    return autoApprove.save(AdminContext.current(), body);
  }

  @RequiresPermission(Permission.WITHDRAWAL_VIEW)
  @GetMapping("/api/admin/withdraw-auto-approve/changes")
  List<AutoApproveOps.ChangeView> autoApproveChanges() {
    return autoApprove.changes();
  }

  @RequiresPermission(Permission.WITHDRAWAL_VIEW)
  @GetMapping("/api/admin/withdrawals/summary")
  FundsOps.Summary withdrawalSummary() {
    return funds.summary();
  }

  @RequiresPermission(Permission.WITHDRAWAL_VIEW)
  @GetMapping("/api/admin/withdrawals/{id}")
  FundsOps.WithdrawalDetail withdrawal(@PathVariable long id) {
    return funds.withdrawal(id);
  }

  @RequiresPermission(Permission.WITHDRAWAL_REVIEW)
  @PostMapping("/api/admin/withdrawals/{id}/approve")
  FundsOps.WithdrawalView approve(@PathVariable long id) {
    return funds.approve(AdminContext.current(), id);
  }

  @RequiresPermission(Permission.WITHDRAWAL_REVIEW)
  @PostMapping("/api/admin/withdrawals/{id}/reject")
  FundsOps.WithdrawalView reject(@PathVariable long id, @RequestBody ReasonBody body) {
    return funds.reject(AdminContext.current(), id, body == null ? null : body.reason());
  }

  @RequiresPermission(Permission.WITHDRAWAL_EMERGENCY_CANCEL)
  @PostMapping("/api/admin/withdrawals/{id}/emergency-cancel")
  FundsOps.WithdrawalView emergencyCancel(@PathVariable long id, @RequestBody ReasonBody body) {
    return funds.emergencyCancel(AdminContext.current(), id, body == null ? null : body.reason());
  }

  @RequiresPermission(Permission.RECON_VIEW)
  @GetMapping("/api/admin/recon")
  Ops.Page<FundsOps.ReconView> recon(@RequestParam(required = false) String status, @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int pageSize) {
    return funds.recon(status, page, pageSize);
  }

  /** 热钱包当前余额和告警下限（对账页上方）；下限在系统配置页改 */
  @RequiresPermission(Permission.RECON_VIEW)
  @GetMapping("/api/admin/hot-wallets")
  List<FundsOps.HotWalletView> hotWallets() {
    return funds.hotWallets();
  }

  @RequiresPermission(Permission.RECON_RESOLVE)
  @PostMapping("/api/admin/recon/{id}/resolve")
  void resolveRecon(@PathVariable long id, @RequestBody NoteBody body) {
    funds.resolveRecon(AdminContext.current(), id, body == null ? null : body.note());
  }

  @RequiresPermission(Permission.TASK_VIEW)
  @GetMapping("/api/admin/tasks")
  Ops.Page<FundsOps.TaskView> tasks(@RequestParam(required = false) String type, @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize) {
    return funds.tasks(type, status, page, pageSize);
  }

  @RequiresPermission(Permission.TASK_RETRY)
  @PostMapping("/api/admin/tasks/{id}/retry")
  void retryTask(@PathVariable long id) {
    funds.retryTask(id);
  }

  // ---------- 通知 ----------

  @RequiresPermission(Permission.NOTIFY_TEMPLATE_VIEW)
  @GetMapping("/api/admin/notify/templates")
  List<NotifyOps.TemplateView> templates() {
    return notify.templates();
  }

  @RequiresPermission(Permission.NOTIFY_TEMPLATE_EDIT)
  @PutMapping("/api/admin/notify/templates/{code}")
  NotifyOps.TemplateView updateTemplate(@PathVariable String code, @RequestBody NotifyOps.TemplatePatch body) {
    return notify.updateTemplate(code, body);
  }

  @RequiresPermission(Permission.NOTIFY_SEND)
  @PostMapping("/api/admin/notify/send")
  void send(@RequestBody NotifyOps.ManualInput body) {
    notify.send(AdminContext.current(), body);
  }

  @RequiresPermission(Permission.NOTIFY_RECORD_VIEW)
  @GetMapping("/api/admin/notify/records")
  Ops.Page<NotifyOps.RecordView> records(@RequestParam(required = false) String channel, @RequestParam(required = false) String status,
      @RequestParam(required = false) String keyword, @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize) {
    return notify.records(channel, status, keyword, page, pageSize);
  }
}
