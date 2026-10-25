package com.forecast.app.admin.ops;

import com.forecast.app.admin.security.AdminContext;
import com.forecast.app.admin.security.Audit;
import com.forecast.app.admin.security.Permission;
import com.forecast.app.common.error.BizException;
import com.forecast.app.common.error.ValidationException;
import com.forecast.app.resolve.api.ResolveDeskApi;
import com.forecast.app.settle.api.SettleAdminApi;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 后台的裁决台、公示与争议（forecast S7；需求说明书第 2.2、4.4、6.2 节，AC-13、AC-30～AC-36）：业务在 resolve 模块（{@link ResolveDeskApi}），
 * 结算预览在 settle（{@link SettleAdminApi#preview}），这里负责审计和高危确认。
 * <ul>
 *   <li>提议、上传截图、E6 / E7 标记和解除：{@code resolve:propose}（不是高危，写审计）。</li>
 *   <li>确认（进入公示）、退回、直接确认：{@code resolve:confirm}（★ 高危：原因必填、确认和直接确认还要勾选）；确认或直接确认的是"作废"时还要 {@code resolve:void}。</li>
 *   <li>处理争议：{@code dispute:handle}（★ 原因必填、勾选确认）。</li>
 *   <li>RQ-17：只要求操作人有权限点，提议人自己有确认权限时可以确认自己的提议（AC-30）。</li>
 * </ul>
 * 不加事务：resolve 的每个操作自己一个事务，提交以后再记"成功"的审计（失败也记，Audit 在独立事务里写）。
 */
@Service
class ResolveOps {

  /**
   * 裁决详情 + 待确认提议的结算预览（守恒数字，W4）。
   *
   * @param proposalPreview 待确认的是结果提议时按它算的结算预览；没有提议、提议作废时为空
   */
  record CaseView(ResolveDeskApi.CaseDetail detail, SettleAdminApi.Preview proposalPreview) {}

  private final ResolveDeskApi desk;
  private final SettleAdminApi settle;
  private final Audit audit;

  ResolveOps(ResolveDeskApi desk, SettleAdminApi settle, Audit audit) {
    this.desk = desk;
    this.settle = settle;
    this.audit = audit;
  }

  static long no(String s, String what) {
    try {
      return Long.parseLong(s == null ? "" : s.strip());
    } catch (NumberFormatException e) {
      throw new BizException("NOT_FOUND", what + "不存在");
    }
  }

  ResolveDeskApi.Page<ResolveDeskApi.CaseItem> cases(String tab, int page, int pageSize) {
    return desk.cases(tab, page, pageSize);
  }

  CaseView detail(String marketNo) {
    ResolveDeskApi.CaseDetail d = desk.detail(no(marketNo, "市场")).orElseThrow(() -> new BizException("NOT_FOUND", "这个市场不在裁决台"));
    return new CaseView(d, previewOf(marketNo, d.summary().proposal()));
  }

  private SettleAdminApi.Preview previewOf(String marketNo, ResolveDeskApi.Proposal p) {
    if (p == null || !"result".equals(p.outcome()) || p.optionNo() == null) return null;
    return settle.preview(no(marketNo, "市场"), p.optionNo(), p.actualStartAt());
  }

  ResolveDeskApi.Attachment upload(AdminContext actor, String marketNo, byte[] content) {
    return audit.run("resolve.attachment", marketNo, false, null, () -> {
      ResolveDeskApi.Attachment a = desk.upload(no(marketNo, "市场"), content, actor.adminId());
      return Audit.Change.of(a, null, Map.of("attachmentNo", a.attachmentNo(), "sha256", a.sha256(), "size", a.size()));
    });
  }

  ResolveDeskApi.File attachment(String attachmentNo) {
    return desk.attachment(no(attachmentNo, "截图")).orElseThrow(() -> new BizException("NOT_FOUND", "截图不存在"));
  }

  CaseView propose(AdminContext actor, String marketNo, ResolveDeskApi.ProposalInput in) {
    return audit.run("resolve.propose", marketNo, false, in == null ? null : cut(in.note()), () -> {
      ResolveDeskApi.CaseDetail d = desk.propose(no(marketNo, "市场"), in, actor.adminId());
      return Audit.Change.of(new CaseView(d, previewOf(marketNo, d.summary().proposal())), null, proposalAfter(d.summary().proposal()));
    });
  }

  /** 确认（★：原因必填、勾选确认；作废的提议还要 resolve:void） */
  CaseView confirm(AdminContext actor, String marketNo, String proposalId, Boolean checked, String reason) {
    String r = highRisk(reason, checked, List.of());
    return audit.run("resolve.confirm", marketNo, true, r, () -> {
      ResolveDeskApi.CaseDetail d = desk.confirm(no(marketNo, "市场"), proposalId, actor.adminId(), actor.has(Permission.RESOLVE_VOID));
      return Audit.Change.of(new CaseView(d, null), Map.of("status", "pending_resolution", "proposalId", proposalId), stateAfter(d));
    });
  }

  /** 退回提议（★：原因必填，原因写进裁决记录） */
  CaseView returnProposal(AdminContext actor, String marketNo, String proposalId, String reason) {
    String r = Audit.requireReason(reason);
    return audit.run("resolve.return", marketNo, true, r, () -> {
      ResolveDeskApi.CaseDetail d = desk.returnProposal(no(marketNo, "市场"), proposalId, r, actor.adminId());
      return Audit.Change.of(new CaseView(d, null), Map.of("proposalId", proposalId), Map.of("proposalId", "-"));
    });
  }

  /** 不等提议，自己填结果直接确认（★：原因、勾选、提议内容的错误一次列出，C56） */
  CaseView decide(AdminContext actor, String marketNo, ResolveDeskApi.ProposalInput in, Boolean checked, String reason) {
    String r = highRisk(reason, checked, desk.checkProposal(no(marketNo, "市场"), in));
    return audit.run("resolve.decide", marketNo, true, r, () -> {
      ResolveDeskApi.CaseDetail d = desk.decide(no(marketNo, "市场"), in, actor.adminId(), actor.has(Permission.RESOLVE_VOID));
      Map<String, Object> after = stateAfter(d);
      after.put("optionNo", in == null ? null : in.optionNo());
      after.put("void", in != null && Boolean.TRUE.equals(in.voidMarket()));
      return Audit.Change.of(new CaseView(d, null), Map.of("status", "pending_resolution"), after);
    });
  }

  CaseView hold(AdminContext actor, String marketNo, String kind, String reason) {
    return audit.run("resolve.hold", marketNo, false, cut(reason), () -> {
      ResolveDeskApi.CaseDetail d = desk.hold(no(marketNo, "市场"), kind, reason, actor.adminId());
      return Audit.Change.of(new CaseView(d, null), null, Map.of("hold", String.valueOf(d.summary().hold()), "holdUntil", String.valueOf(d.summary().holdUntil())));
    });
  }

  CaseView release(AdminContext actor, String marketNo, String reason) {
    return audit.run("resolve.release", marketNo, false, cut(reason), () -> {
      ResolveDeskApi.CaseDetail d = desk.release(no(marketNo, "市场"), reason, actor.adminId());
      return Audit.Change.of(new CaseView(d, null), null, Map.of("hold", "-"));
    });
  }

  ResolveDeskApi.Page<ResolveDeskApi.Dispute> disputes(String status, int page, int pageSize) {
    return desk.disputes(status, page, pageSize);
  }

  ResolveDeskApi.CaseDetail disputeCase(String marketNo) {
    return desk.detail(no(marketNo, "市场")).orElseThrow(() -> new BizException("NOT_FOUND", "这个市场不在裁决台"));
  }

  /** 处理争议（★：原因必填、勾选确认；结论 note 公开在市场详情） */
  ResolveDeskApi.CaseDetail decideDisputes(AdminContext actor, String marketNo, ResolveDeskApi.DecisionInput in, Boolean checked, String reason) {
    String r = highRisk(reason, checked, List.of());
    return audit.run("dispute.decide", marketNo, true, r, () -> {
      ResolveDeskApi.CaseDetail d = desk.decideDisputes(no(marketNo, "市场"), in, actor.adminId());
      Map<String, Object> after = stateAfter(d);
      after.put("decision", d.decision());
      after.put("resultOptionNo", d.summary().resultOptionNo());
      return Audit.Change.of(d, Map.of("status", "disputed"), after);
    });
  }

  /** 高危确认：原因必填、勾选确认，和别的字段错误一次列出（C56）；返回去掉首尾空白的原因 */
  private static String highRisk(String reason, Boolean checked, List<ValidationException.Field> more) {
    List<ValidationException.Field> errs = new ArrayList<>();
    try {
      Audit.requireReason(reason);
    } catch (ValidationException e) {
      errs.addAll(e.fields());
    }
    if (!Boolean.TRUE.equals(checked)) errs.add(new ValidationException.Field("checked", "required"));
    errs.addAll(more);
    if (!errs.isEmpty()) throw new ValidationException(errs);
    return reason.strip();
  }

  private static Map<String, Object> stateAfter(ResolveDeskApi.CaseDetail d) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("status", d.summary().marketStatus());
    m.put("resultOptionNo", d.summary().resultOptionNo());
    m.put("disputeDeadlineAt", d.summary().disputeDeadlineAt());
    return m;
  }

  private static Map<String, Object> proposalAfter(ResolveDeskApi.Proposal p) {
    Map<String, Object> m = new LinkedHashMap<>();
    if (p == null) return m;
    m.put("proposalId", p.proposalId());
    m.put("outcome", p.outcome());
    m.put("optionNo", p.optionNo());
    m.put("basis", p.basis());
    return m;
  }

  private static String cut(String s) {
    if (s == null || s.isBlank()) return null;
    String t = s.strip();
    return t.length() <= 500 ? t : t.substring(0, 500);
  }
}
