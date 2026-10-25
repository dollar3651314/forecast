package com.forecast.app.admin.ops;

import com.forecast.app.admin.security.AdminContext;
import com.forecast.app.admin.security.Permission;
import com.forecast.app.admin.security.RequiresPermission;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后台"风控告警"和关联账户（forecast S10，需求说明书第 6.2 节；权限点 {@code risk_alert:view}、{@code risk_alert:handle}，项目地图约定 12）。
 * 业务和审计在 {@link RiskOps}。
 */
@RestController
class RiskAdminController {

  private final RiskOps ops;

  RiskAdminController(RiskOps ops) {
    this.ops = ops;
  }

  /** 告警列表：kind（late_large_bet / linked_opposite / new_account_longshot / deposit_no_bet）、status（new / seen / handled / false_positive）、keyword（客户） */
  @RequiresPermission(Permission.RISK_ALERT_VIEW)
  @GetMapping("/api/admin/risk-alerts")
  Ops.Page<RiskOps.AlertView> list(@RequestParam(required = false) String kind, @RequestParam(required = false) String status,
      @RequestParam(required = false) String keyword, @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize) {
    return ops.list(kind, status, keyword, page, pageSize);
  }

  /** 待处理（新 + 已查看）合计、按类型、按状态的条数 */
  @RequiresPermission(Permission.RISK_ALERT_VIEW)
  @GetMapping("/api/admin/risk-alerts/counts")
  Map<String, Object> counts() {
    return ops.counts();
  }

  /** 改处理状态（已查看 / 已处理 / 误报 / 重新打开）；已处理、误报要写处理说明 */
  @RequiresPermission(Permission.RISK_ALERT_HANDLE)
  @PostMapping("/api/admin/risk-alerts/{id}/status")
  RiskOps.AlertView setStatus(@PathVariable long id, @RequestBody(required = false) RiskOps.StatusBody body) {
    return ops.setStatus(AdminContext.current(), id, body);
  }

  /** 客户的关联账户（同设备、同 IP、同充值来源地址、同提现地址） */
  @RequiresPermission(Permission.CUSTOMER_VIEW)
  @GetMapping("/api/admin/customers/{id}/links")
  List<RiskOps.LinkView> links(@PathVariable long id) {
    return ops.links(id);
  }
}
