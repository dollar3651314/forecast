package com.forecast.app.admin.ops;

import com.forecast.app.admin.security.AdminContext;
import com.forecast.app.admin.security.Audit;
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
 * 后台的结算、退款、彩池对账、补偿（forecast S5、S7）：业务在 settle 模块（{@link SettleAdminApi}），这里负责审计。
 * <ul>
 *   <li>S5 的临时入口"超管手工填结果直接结算"S7 已删除（SQ-10）：结果走裁决台（{@code ResolveOps}）。</li>
 *   <li>人工作废（E5 比赛取消等）：{@code resolve:void}，高危，原因必填；业务在 resolve 的裁决台。</li>
 *   <li>补偿（E13）：{@code settle:compensate}，高危——原因必填、勾选确认，一个有权限的人确认即入账（RQ-17）。</li>
 *   <li>结算异常重试、对账差异标记已处理：{@code pool_recon:resolve}，原因 / 处理说明必填。</li>
 * </ul>
 * 不加事务：settle 的每个操作自己一个事务，提交以后再记"成功"的审计（失败也记，Audit 在独立事务里写）。
 */
@Service
class SettleOps {

  private final SettleAdminApi settle;
  private final ResolveDeskApi desk;
  private final Audit audit;

  SettleOps(SettleAdminApi settle, ResolveDeskApi desk, Audit audit) {
    this.settle = settle;
    this.desk = desk;
    this.audit = audit;
  }

  static long no(String s, String what) {
    try {
      return Long.parseLong(s == null ? "" : s.strip());
    } catch (NumberFormatException e) {
      throw new BizException("NOT_FOUND", what + "不存在");
    }
  }

  SettleAdminApi.Outcome outcome(String marketNo) {
    return settle.outcome(no(marketNo, "市场")).orElseThrow(() -> new BizException("NOT_FOUND", "市场不存在"));
  }

  /**
   * 人工作废（E5 比赛取消等；{@code resolve:void}，高危、原因必填）：业务在裁决台（{@link ResolveDeskApi#voidMarket}，写裁决记录、结束待处理的争议），
   * 返回结算情况（退款单）。S5 起的接口地址不变。
   */
  SettleAdminApi.Outcome voidManually(AdminContext actor, String marketNo, String reason) {
    String r = Audit.requireReason(reason);
    return audit.run("market.void_manual", marketNo, true, r, () -> {
      SettleAdminApi.Outcome before = outcome(marketNo);
      desk.voidMarket(no(marketNo, "市场"), r, actor.adminId());
      SettleAdminApi.Outcome o = outcome(marketNo);
      Map<String, Object> after = new LinkedHashMap<>();
      after.put("status", o.marketStatus());
      if (o.refund() != null) after.put("refundRunNo", o.refund().runNo());
      return Audit.Change.of(o, Map.of("status", before.marketStatus()), after);
    });
  }

  SettleAdminApi.Preview preview(String marketNo, String optionNo, String actualStartAt) {
    return settle.preview(no(marketNo, "市场"), optionNo, actualStartAt);
  }

  // ---------------------------------------------------------------- 补偿（E13，settle:compensate）

  SettleAdminApi.CompPlan compensationPreview(String marketNo, String correctOptionNo) {
    return settle.compensationPreview(no(marketNo, "市场"), correctOptionNo);
  }

  /** 确认补偿（高危：原因必填、勾选确认；一次列出全部错误） */
  SettleAdminApi.Compensation compensate(AdminContext actor, String marketNo, String correctOptionNo, Boolean checked, String reason) {
    List<ValidationException.Field> errs = new ArrayList<>();
    try {
      Audit.requireReason(reason);
    } catch (ValidationException e) {
      errs.addAll(e.fields());
    }
    if (!Boolean.TRUE.equals(checked)) errs.add(new ValidationException.Field("checked", "required"));
    errs.addAll(settle.checkCompensation(no(marketNo, "市场"), correctOptionNo));
    if (!errs.isEmpty()) throw new ValidationException(errs);
    String r = reason.strip();
    return audit.run("settle.compensate", marketNo, true, r, () -> {
      SettleAdminApi.Compensation c = settle.compensate(no(marketNo, "市场"), correctOptionNo, r, actor.adminId());
      Map<String, Object> after = new LinkedHashMap<>();
      after.put("compensationNo", c.compensationNo());
      after.put("originalOptionNo", c.originalOptionNo());
      after.put("correctOptionNo", c.correctOptionNo());
      after.put("total", c.total());
      after.put("userCount", c.userCount());
      return Audit.Change.of(c, Map.of("compensated", false), after);
    });
  }

  SettleAdminApi.Page<SettleAdminApi.Compensation> compensations(int page, int pageSize) {
    return settle.compensations(page, pageSize);
  }

  SettleAdminApi.Compensation compensation(String compensationNo) {
    return settle.compensation(no(compensationNo, "补偿单")).orElseThrow(() -> new BizException("NOT_FOUND", "补偿单不存在"));
  }

  SettleAdminApi.Report report(String from, String to) {
    return settle.report(from, to);
  }

  SettleAdminApi.Page<SettleAdminApi.Run> runs(String status, String kind, int page, int pageSize) {
    return settle.runs(status, kind, page, pageSize);
  }

  SettleAdminApi.RunDetail run(String runNo, int page, int pageSize) {
    return settle.run(no(runNo, "结算单"), page, pageSize).orElseThrow(() -> new BizException("NOT_FOUND", "结算单不存在"));
  }

  SettleAdminApi.Run retry(AdminContext actor, String runNo, String reason) {
    String r = Audit.requireReason(reason);
    return audit.run("settle.retry", runNo, true, r, () -> {
      SettleAdminApi.Run before = run(runNo, 1, 1).run();
      SettleAdminApi.Run after = settle.retry(no(runNo, "结算单"), String.valueOf(actor.adminId()));
      return Audit.Change.of(after, Map.of("status", before.status()), Map.of("status", after.status()));
    });
  }

  SettleAdminApi.Page<SettleAdminApi.Issue> issues(String status, int page, int pageSize) {
    return settle.issues(status, page, pageSize);
  }

  SettleAdminApi.Issue resolveIssue(AdminContext actor, String id, String note) {
    long issueId = no(id, "差异");
    return audit.run("pool_recon.resolve", id, false, note == null ? null : note.strip(), () -> {
      SettleAdminApi.Issue i = settle.resolveIssue(issueId, actor.adminId(), note);
      return Audit.Change.of(i, Map.of("status", "open"), Map.of("status", i.status(), "note", i.note()));
    });
  }
}
