package com.forecast.app.admin.ops;

import com.forecast.app.admin.security.AdminContext;
import com.forecast.app.admin.security.Permission;
import com.forecast.app.admin.security.RequiresPermission;
import com.forecast.app.resolve.api.ResolveDeskApi;
import java.io.IOException;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 后台"裁决台""公示与争议"接口（forecast S7，需求说明书第 6.2 节；页面在原型确认后做，S9）。每个接口声明权限点（项目地图约定 12），审计在 {@link ResolveOps}。
 * 结算预览在 {@code SettleAdminController}（{@code GET /api/admin/markets/{publicNo}/settle-preview}），人工作废仍是 {@code POST /api/admin/markets/{publicNo}/void}。
 */
@RestController
class ResolveAdminController {

  /** 提议 / 直接确认的内容（直接确认另带 checked、reason） */
  record ProposalBody(String optionNo, Boolean voidMarket, String basis, String actualStartAt, List<String> evidenceUrls, List<String> attachmentNos,
      String note, Boolean checked, String reason) {

    ResolveDeskApi.ProposalInput input() {
      return new ResolveDeskApi.ProposalInput(optionNo, voidMarket, basis, actualStartAt, evidenceUrls, attachmentNos, note);
    }
  }

  record ConfirmBody(Boolean checked, String reason) {}

  record ReasonBody(String reason) {}

  record HoldBody(String kind, String reason) {}

  record DecisionBody(String decision, String optionNo, String note, Boolean checked, String reason) {}

  private final ResolveOps ops;

  ResolveAdminController(ResolveOps ops) {
    this.ops = ops;
  }

  /** 裁决台列表：tab = awaiting_proposal / awaiting_confirm / auto_failed / on_hold / overdue / publicizing / disputed（不传为全部）；超时的在前、标红字段 overdue */
  @RequiresPermission(Permission.RESOLVE_PROPOSE)
  @GetMapping("/api/admin/resolve/cases")
  ResolveDeskApi.Page<ResolveDeskApi.CaseItem> cases(@RequestParam(required = false) String tab, @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int pageSize) {
    return ops.cases(tab, page, pageSize);
  }

  /** 一个市场的裁决详情：进度、提议（带结算预览）、裁决记录、争议、截图 */
  @RequiresPermission(Permission.RESOLVE_PROPOSE)
  @GetMapping("/api/admin/resolve/cases/{publicNo}")
  ResolveOps.CaseView detail(@PathVariable String publicNo) {
    return ops.detail(publicNo);
  }

  /**
   * 上传一张存档截图（multipart/form-data 的字段 file；PNG / JPG，≤ 2 MB）。不在映射上限定 consumes：别的类型的请求也先过权限检查（没有权限 403），
   * 再报 file 必填（AdminSecurityTest 逐个接口核对 403）。
   */
  @RequiresPermission(Permission.RESOLVE_PROPOSE)
  @PostMapping("/api/admin/resolve/cases/{publicNo}/attachments")
  ResolveDeskApi.Attachment upload(@PathVariable String publicNo, @RequestParam(required = false) MultipartFile file) throws IOException {
    return ops.upload(AdminContext.current(), publicNo, file == null || file.isEmpty() ? null : file.getBytes());
  }

  /** 截图原图（不缓存） */
  @RequiresPermission(Permission.MARKET_VIEW)
  @GetMapping("/api/admin/resolve/attachments/{attachmentNo}")
  ResponseEntity<byte[]> attachment(@PathVariable String attachmentNo) {
    ResolveDeskApi.File f = ops.attachment(attachmentNo);
    return ResponseEntity.ok().contentType(MediaType.parseMediaType(f.contentType())).cacheControl(CacheControl.noStore()).body(f.content());
  }

  /** 提议（结果 + 依据链接 + 截图 + 理由；或提议作废） */
  @RequiresPermission(Permission.RESOLVE_PROPOSE)
  @PostMapping("/api/admin/resolve/cases/{publicNo}/proposals")
  ResolveOps.CaseView propose(@PathVariable String publicNo, @RequestBody(required = false) ProposalBody body) {
    return ops.propose(AdminContext.current(), publicNo, body == null ? null : body.input());
  }

  /** 确认提议（结果 → 公示中；作废 → 已作废，还要 resolve:void） */
  @RequiresPermission(Permission.RESOLVE_CONFIRM)
  @PostMapping("/api/admin/resolve/cases/{publicNo}/proposals/{proposalId}/confirm")
  ResolveOps.CaseView confirm(@PathVariable String publicNo, @PathVariable String proposalId, @RequestBody(required = false) ConfirmBody body) {
    return ops.confirm(AdminContext.current(), publicNo, proposalId, body == null ? null : body.checked(), body == null ? null : body.reason());
  }

  /** 退回提议（原因必填），市场留在待裁决 */
  @RequiresPermission(Permission.RESOLVE_CONFIRM)
  @PostMapping("/api/admin/resolve/cases/{publicNo}/proposals/{proposalId}/return")
  ResolveOps.CaseView returnProposal(@PathVariable String publicNo, @PathVariable String proposalId, @RequestBody(required = false) ReasonBody body) {
    return ops.returnProposal(AdminContext.current(), publicNo, proposalId, body == null ? null : body.reason());
  }

  /** 不等提议，自己填结果直接确认（进入公示；作废还要 resolve:void） */
  @RequiresPermission(Permission.RESOLVE_CONFIRM)
  @PostMapping("/api/admin/resolve/cases/{publicNo}/decide")
  ResolveOps.CaseView decide(@PathVariable String publicNo, @RequestBody(required = false) ProposalBody body) {
    ProposalBody b = body == null ? new ProposalBody(null, null, null, null, null, null, null, null, null) : body;
    return ops.decide(AdminContext.current(), publicNo, b.input(), b.checked(), b.reason());
  }

  /** E6 延期（kind = postponed）/ E7 腰斩（abandoned）：等待期间不能提议，48 小时没解除系统作废 */
  @RequiresPermission(Permission.RESOLVE_PROPOSE)
  @PostMapping("/api/admin/resolve/cases/{publicNo}/hold")
  ResolveOps.CaseView hold(@PathVariable String publicNo, @RequestBody(required = false) HoldBody body) {
    return ops.hold(AdminContext.current(), publicNo, body == null ? null : body.kind(), body == null ? null : body.reason());
  }

  /** 解除等待（已完赛 / 官方已宣布比分） */
  @RequiresPermission(Permission.RESOLVE_PROPOSE)
  @PostMapping("/api/admin/resolve/cases/{publicNo}/release")
  ResolveOps.CaseView release(@PathVariable String publicNo, @RequestBody(required = false) ReasonBody body) {
    return ops.release(AdminContext.current(), publicNo, body == null ? null : body.reason());
  }

  /** 争议列表：status = open / handled（不传为全部），看得到争议人编号 */
  @RequiresPermission(Permission.DISPUTE_VIEW)
  @GetMapping("/api/admin/resolve/disputes")
  ResolveDeskApi.Page<ResolveDeskApi.Dispute> disputes(@RequestParam(required = false) String status, @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int pageSize) {
    return ops.disputes(status, page, pageSize);
  }

  /** 一个市场的公示和争议（同裁决详情，给只有 dispute:view 的人） */
  @RequiresPermission(Permission.DISPUTE_VIEW)
  @GetMapping("/api/admin/resolve/disputes/{publicNo}")
  ResolveDeskApi.CaseDetail disputeCase(@PathVariable String publicNo) {
    return ops.disputeCase(publicNo);
  }

  /** 处理争议：decision = uphold / overturn（optionNo 新结果）/ void；note 是公开的结论和理由；处理完直接结算 */
  @RequiresPermission(Permission.DISPUTE_HANDLE)
  @PostMapping("/api/admin/resolve/cases/{publicNo}/disputes/decide")
  ResolveDeskApi.CaseDetail decideDisputes(@PathVariable String publicNo, @RequestBody(required = false) DecisionBody body) {
    DecisionBody b = body == null ? new DecisionBody(null, null, null, null, null) : body;
    return ops.decideDisputes(AdminContext.current(), publicNo, new ResolveDeskApi.DecisionInput(b.decision(), b.optionNo(), b.note()), b.checked(), b.reason());
  }
}
