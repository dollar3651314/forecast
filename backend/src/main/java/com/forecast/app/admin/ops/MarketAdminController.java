package com.forecast.app.admin.ops;

import com.forecast.app.admin.security.AdminContext;
import com.forecast.app.admin.security.Permission;
import com.forecast.app.admin.security.RequiresPermission;
import com.forecast.app.market.api.I18nText;
import com.forecast.app.market.api.MarketAdminApi;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后台"市场管理"接口（forecast S3，需求说明书第 6.2 节；页面在原型确认后做）。路径里的 {publicNo} 是市场的对外编号（字符串）。
 * 字段见 {@link MarketAdminApi}；每个接口声明权限点（项目地图约定 12），审计在 {@link MarketOps}。
 */
@RestController
class MarketAdminController {

  record ReasonBody(String reason) {}

  record PublishBody(Boolean checked) {}

  record ClarifyBody(I18nText text, String reason) {}

  record CloseBody(String closeAt, String reason) {}

  record FeeBody(String feeRate, String reason) {}

  private final MarketOps ops;

  MarketAdminController(MarketOps ops) {
    this.ops = ops;
  }

  /** 列表：status（小写状态）、category、submitted（true = 待上架）、keyword（题目或编号） */
  @RequiresPermission(Permission.MARKET_VIEW)
  @GetMapping("/api/admin/markets")
  MarketAdminApi.Page<MarketAdminApi.Summary> list(@RequestParam(required = false) String status, @RequestParam(required = false) String category,
      @RequestParam(required = false) Boolean submitted, @RequestParam(required = false) String keyword, @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int pageSize) {
    return ops.list(status, category, submitted, keyword, page, pageSize);
  }

  @RequiresPermission(Permission.MARKET_VIEW)
  @GetMapping("/api/admin/markets/{publicNo}")
  MarketAdminApi.Detail detail(@PathVariable String publicNo) {
    return ops.detail(publicNo);
  }

  /** 新建草稿 */
  @RequiresPermission(Permission.MARKET_EDIT)
  @PostMapping("/api/admin/markets")
  MarketAdminApi.Detail create(@RequestBody MarketAdminApi.DraftInput body) {
    return ops.create(AdminContext.current(), body);
  }

  /** 编辑草稿（上架后返回 409 MARKET_NOT_EDITABLE） */
  @RequiresPermission(Permission.MARKET_EDIT)
  @PutMapping("/api/admin/markets/{publicNo}")
  MarketAdminApi.Detail update(@PathVariable String publicNo, @RequestBody MarketAdminApi.DraftInput body) {
    return ops.update(AdminContext.current(), publicNo, body);
  }

  /** 删除草稿 */
  @RequiresPermission(Permission.MARKET_EDIT)
  @DeleteMapping("/api/admin/markets/{publicNo}")
  ResponseEntity<Void> delete(@PathVariable String publicNo) {
    ops.delete(publicNo);
    return ResponseEntity.noContent().build();
  }

  /** 提交上架（只加标记，状态仍是草稿） */
  @RequiresPermission(Permission.MARKET_EDIT)
  @PostMapping("/api/admin/markets/{publicNo}/submit")
  MarketAdminApi.Detail submit(@PathVariable String publicNo) {
    return ops.submit(publicNo);
  }

  /** 退回草稿（理由必填） */
  @RequiresPermission(Permission.MARKET_PUBLISH)
  @PostMapping("/api/admin/markets/{publicNo}/return")
  MarketAdminApi.Detail returnDraft(@PathVariable String publicNo, @RequestBody(required = false) ReasonBody body) {
    return ops.returnDraft(publicNo, body == null ? null : body.reason());
  }

  /** 上架：{"checked": true}（勾选"已检查以上各项"） */
  @RequiresPermission(Permission.MARKET_PUBLISH)
  @PostMapping("/api/admin/markets/{publicNo}/publish")
  MarketAdminApi.Detail publish(@PathVariable String publicNo, @RequestBody(required = false) PublishBody body) {
    return ops.publish(AdminContext.current(), publicNo, body == null ? null : body.checked());
  }

  @RequiresPermission(Permission.MARKET_PAUSE)
  @PostMapping("/api/admin/markets/{publicNo}/pause")
  MarketAdminApi.Detail pause(@PathVariable String publicNo, @RequestBody(required = false) ReasonBody body) {
    return ops.pause(AdminContext.current(), publicNo, body == null ? null : body.reason());
  }

  @RequiresPermission(Permission.MARKET_PAUSE)
  @PostMapping("/api/admin/markets/{publicNo}/resume")
  MarketAdminApi.Detail resume(@PathVariable String publicNo, @RequestBody(required = false) ReasonBody body) {
    return ops.resume(AdminContext.current(), publicNo, body == null ? null : body.reason());
  }

  /** 提前截止：{"closeAt": "2026-11-01T14:00:00Z", "reason": "…"}（只能提前） */
  @RequiresPermission(Permission.MARKET_PAUSE)
  @PostMapping("/api/admin/markets/{publicNo}/advance-close")
  MarketAdminApi.Detail advanceClose(@PathVariable String publicNo, @RequestBody(required = false) CloseBody body) {
    return ops.advanceClose(AdminContext.current(), publicNo, body == null ? null : body.closeAt(), body == null ? null : body.reason());
  }

  /** 截止前取消（全额退款） */
  @RequiresPermission(Permission.MARKET_CANCEL)
  @PostMapping("/api/admin/markets/{publicNo}/cancel")
  MarketAdminApi.Detail cancel(@PathVariable String publicNo, @RequestBody(required = false) ReasonBody body) {
    return ops.cancel(AdminContext.current(), publicNo, body == null ? null : body.reason());
  }

  /** 补充说明：{"text": {"en","zhCN","zhTW"}, "reason": "…"} */
  @RequiresPermission(Permission.MARKET_CLARIFY)
  @PostMapping("/api/admin/markets/{publicNo}/clarifications")
  MarketAdminApi.Detail clarify(@PathVariable String publicNo, @RequestBody(required = false) ClarifyBody body) {
    return ops.clarify(AdminContext.current(), publicNo, body == null ? null : body.text(), body == null ? null : body.reason());
  }

  /** 改本市场费率：{"feeRate": "8", "reason": "…"}（百分比） */
  @RequiresPermission(Permission.FEE_EDIT)
  @PutMapping("/api/admin/markets/{publicNo}/fee")
  MarketAdminApi.FeeChange fee(@PathVariable String publicNo, @RequestBody(required = false) FeeBody body) {
    return ops.changeFee(AdminContext.current(), publicNo, body == null ? null : body.feeRate(), body == null ? null : body.reason());
  }
}
