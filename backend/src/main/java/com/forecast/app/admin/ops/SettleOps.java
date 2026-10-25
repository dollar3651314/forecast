package com.forecast.app.admin.ops;

import com.forecast.app.admin.security.AdminContext;
import com.forecast.app.admin.security.Audit;
import com.forecast.app.common.error.BizException;
import com.forecast.app.common.error.ValidationException;
import com.forecast.app.settle.api.SettleAdminApi;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 后台的结算、退款、彩池对账（forecast S5）：业务在 settle 模块（{@link SettleAdminApi}），这里负责审计。
 * <ul>
 *   <li>超管手工填结果直接结算（S7 正式裁决台之前的临时入口）：{@code resolve:confirm}，高危——原因必填、必须勾选确认，审计"前 → 后"（需求第 2.2 节、NF-08）。</li>
 *   <li>人工作废（E5 比赛取消等）：{@code resolve:void}，高危，原因必填。</li>
 *   <li>结算异常重试、对账差异标记已处理：{@code pool_recon:resolve}，原因 / 处理说明必填。</li>
 * </ul>
 * 不加事务：settle 的每个操作自己一个事务，提交以后再记"成功"的审计（失败也记，Audit 在独立事务里写）。
 */
@Service
class SettleOps {

  private final SettleAdminApi settle;
  private final Audit audit;

  SettleOps(SettleAdminApi settle, Audit audit) {
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

  SettleAdminApi.Outcome outcome(String marketNo) {
    return settle.outcome(no(marketNo, "市场")).orElseThrow(() -> new BizException("NOT_FOUND", "市场不存在"));
  }

  SettleAdminApi.Outcome settleManually(AdminContext actor, String marketNo, String optionNo, String actualStartAt, Boolean checked, String reason) {
    // 一次列出全部错误（C56）：原因、勾选确认、选项、实际开赛时间
    List<ValidationException.Field> errs = new ArrayList<>();
    try {
      Audit.requireReason(reason);
    } catch (ValidationException e) {
      errs.addAll(e.fields());
    }
    if (!Boolean.TRUE.equals(checked)) errs.add(new ValidationException.Field("checked", "required"));
    errs.addAll(settle.checkManual(no(marketNo, "市场"), optionNo, actualStartAt));
    if (!errs.isEmpty()) throw new ValidationException(errs);
    String r = reason.strip();
    return audit.run("market.settle_manual", marketNo, true, r, () -> {
      SettleAdminApi.Outcome before = outcome(marketNo);
      SettleAdminApi.Outcome o = settle.settleManually(no(marketNo, "市场"), optionNo, actualStartAt, String.valueOf(actor.adminId()));
      Map<String, Object> after = new LinkedHashMap<>();
      after.put("status", o.marketStatus());
      after.put("settlePhase", o.settlePhase());
      after.put("winningOptionNo", optionNo);
      if (actualStartAt != null && !actualStartAt.isBlank()) after.put("actualStartAt", actualStartAt);
      if (o.payout() != null) {
        after.put("runNo", o.payout().runNo());
        after.put("sumPayout", o.payout().sumPayout());
        after.put("sumFee", o.payout().sumFee());
        after.put("dust", o.payout().dust());
      }
      if (o.refund() != null) after.put("refundRunNo", o.refund().runNo());
      return Audit.Change.of(o, Map.of("status", before.marketStatus(), "settlePhase", before.settlePhase()), after);
    });
  }

  SettleAdminApi.Outcome voidManually(AdminContext actor, String marketNo, String reason) {
    String r = Audit.requireReason(reason);
    return audit.run("market.void_manual", marketNo, true, r, () -> {
      SettleAdminApi.Outcome before = outcome(marketNo);
      SettleAdminApi.Outcome o = settle.voidManually(no(marketNo, "市场"), r, String.valueOf(actor.adminId()));
      Map<String, Object> after = new LinkedHashMap<>();
      after.put("status", o.marketStatus());
      if (o.refund() != null) after.put("refundRunNo", o.refund().runNo());
      return Audit.Change.of(o, Map.of("status", before.marketStatus()), after);
    });
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
