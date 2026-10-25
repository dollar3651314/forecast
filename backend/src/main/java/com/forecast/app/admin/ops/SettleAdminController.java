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
 * 后台"结算与补偿""彩池对账"接口（forecast S5，需求说明书第 6.2 节；页面在原型确认后做）。每个接口声明权限点（项目地图约定 12），审计在 {@link SettleOps}。
 */
@RestController
class SettleAdminController {

  /** 超管手工填结果：{"optionNo": "…", "actualStartAt": "2026-11-01T14:00:00Z"（可空）, "checked": true, "reason": "…"} */
  record SettleBody(String optionNo, String actualStartAt, Boolean checked, String reason) {}

  record ReasonBody(String reason) {}

  record NoteBody(String note) {}

  private final SettleOps ops;

  SettleAdminController(SettleOps ops) {
    this.ops = ops;
  }

  /** 超管手工填结果直接结算（临时入口，S7 换成裁决台）：市场必须是待裁决；同一市场重复提交只结算一次 */
  @RequiresPermission(Permission.RESOLVE_CONFIRM)
  @PostMapping("/api/admin/markets/{publicNo}/settle")
  SettleAdminApi.Outcome settle(@PathVariable String publicNo, @RequestBody(required = false) SettleBody body) {
    SettleBody b = body == null ? new SettleBody(null, null, null, null) : body;
    return ops.settleManually(AdminContext.current(), publicNo, b.optionNo(), b.actualStartAt(), b.checked(), b.reason());
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
