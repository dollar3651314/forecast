package com.forecast.app.admin.ops;

import com.forecast.app.admin.security.AdminContext;
import com.forecast.app.admin.security.Permission;
import com.forecast.app.admin.security.RequiresPermission;
import com.forecast.app.settle.api.SettleAdminApi;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后台"结算与补偿""彩池对账"接口（forecast S5、S7，需求说明书第 6.2 节；页面在原型确认后做，S9）。S5 的"超管手工填结果直接结算"S7 已删除（SQ-10）。每个接口声明权限点（项目地图约定 12），审计在 {@link SettleOps}。
 */
@RestController
class SettleAdminController {

  /** 补偿：{"correctOptionNo": "…" 或 "void", "checked": true, "reason": "…"} */
  record CompensateBody(String correctOptionNo, Boolean checked, String reason) {}

  record ReasonBody(String reason) {}

  record NoteBody(String note) {}

  private final SettleOps ops;

  SettleAdminController(SettleOps ops) {
    this.ops = ops;
  }

  /** 人工作废（待裁决、公示中、争议中 → 已作废，全额退款；E5 比赛取消等） */
  @RequiresPermission(Permission.RESOLVE_VOID)
  @PostMapping("/api/admin/markets/{publicNo}/void")
  SettleAdminApi.Outcome voidMarket(@PathVariable String publicNo, @RequestBody(required = false) ReasonBody body) {
    return ops.voidManually(AdminContext.current(), publicNo, body == null ? null : body.reason());
  }

  /** 一个市场的结算情况（结算单、退款单） */
  @RequiresPermission(Permission.MARKET_VIEW)
  @GetMapping("/api/admin/markets/{publicNo}/settlement")
  SettleAdminApi.Outcome outcome(@PathVariable String publicNo) {
    return ops.outcome(publicNo);
  }

  /** 结算记录、结算异常：status（paying / done / abnormal）、kind（payout / refund） */
  @RequiresPermission(Permission.POOL_RECON_VIEW)
  @GetMapping("/api/admin/settle/runs")
  SettleAdminApi.Page<SettleAdminApi.Run> runs(@RequestParam(required = false) String status, @RequestParam(required = false) String kind,
      @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize) {
    return ops.runs(status, kind, page, pageSize);
  }

  /** 一张结算单 / 退款单和明细（分页） */
  @RequiresPermission(Permission.POOL_RECON_VIEW)
  @GetMapping("/api/admin/settle/runs/{runNo}")
  SettleAdminApi.RunDetail run(@PathVariable String runNo, @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "100") int pageSize) {
    return ops.run(runNo, page, pageSize);
  }

  /** 结算异常处理后重试（原因必填） */
  @RequiresPermission(Permission.POOL_RECON_RESOLVE)
  @PostMapping("/api/admin/settle/runs/{runNo}/retry")
  SettleAdminApi.Run retry(@PathVariable String runNo, @RequestBody(required = false) ReasonBody body) {
    return ops.retry(AdminContext.current(), runNo, body == null ? null : body.reason());
  }

  /** 结算预览（守恒数字）：按 optionNo 这个结果（和实际开赛时间）现在结算会怎样；裁决台用（S7） */
  @RequiresPermission(Permission.RESOLVE_PROPOSE)
  @GetMapping("/api/admin/markets/{publicNo}/settle-preview")
  SettleAdminApi.Preview preview(@PathVariable String publicNo, @RequestParam(required = false) String optionNo,
      @RequestParam(required = false) String actualStartAt) {
    return ops.preview(publicNo, optionNo, actualStartAt);
  }

  /** 补偿预览（E13）：correctOptionNo = 正确结果的选项编号或 void → 每个客户应补多少 */
  @RequiresPermission(Permission.SETTLE_COMPENSATE)
  @GetMapping("/api/admin/markets/{publicNo}/compensation-preview")
  SettleAdminApi.CompPlan compensationPreview(@PathVariable String publicNo, @RequestParam(required = false) String correctOptionNo) {
    return ops.compensationPreview(publicNo, correctOptionNo);
  }

  /** 确认补偿并入账（高危：原因必填、勾选确认；每个市场一次） */
  @RequiresPermission(Permission.SETTLE_COMPENSATE)
  @PostMapping("/api/admin/markets/{publicNo}/compensations")
  SettleAdminApi.Compensation compensate(@PathVariable String publicNo, @RequestBody(required = false) CompensateBody body) {
    CompensateBody b = body == null ? new CompensateBody(null, null, null) : body;
    return ops.compensate(AdminContext.current(), publicNo, b.correctOptionNo(), b.checked(), b.reason());
  }

  /** 补偿单列表（报表里单列的补偿支出） */
  @RequiresPermission(Permission.REPORT_VIEW)
  @GetMapping("/api/admin/settle/compensations")
  SettleAdminApi.Page<SettleAdminApi.Compensation> compensations(@RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize) {
    return ops.compensations(page, pageSize);
  }

  /** 一张补偿单和明细 */
  @RequiresPermission(Permission.REPORT_VIEW)
  @GetMapping("/api/admin/settle/compensations/{compensationNo}")
  SettleAdminApi.Compensation compensation(@PathVariable String compensationNo) {
    return ops.compensation(compensationNo);
  }

  /** 平台收入（抽成 + 尾差）和补偿支出（单列）：from、to 是 UTC 日期，含两头 */
  @RequiresPermission(Permission.REPORT_VIEW)
  @GetMapping("/api/admin/reports/settle")
  SettleAdminApi.Report report(@RequestParam(required = false) String from, @RequestParam(required = false) String to) {
    return ops.report(from, to);
  }

  /** 彩池对账差异：status（open / resolved） */
  @RequiresPermission(Permission.POOL_RECON_VIEW)
  @GetMapping("/api/admin/pool-recon/issues")
  SettleAdminApi.Page<SettleAdminApi.Issue> issues(@RequestParam(required = false) String status, @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int pageSize) {
    return ops.issues(status, page, pageSize);
  }

  /** 标记已处理（处理说明必填） */
  @RequiresPermission(Permission.POOL_RECON_RESOLVE)
  @PostMapping("/api/admin/pool-recon/issues/{id}/resolve")
  SettleAdminApi.Issue resolve(@PathVariable String id, @RequestBody(required = false) NoteBody body) {
    return ops.resolveIssue(AdminContext.current(), id, body == null ? null : body.note());
  }
}
