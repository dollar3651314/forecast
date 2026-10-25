package com.forecast.app.admin.ops;

import com.forecast.app.admin.security.AdminContext;
import com.forecast.app.admin.security.Audit;
import com.forecast.app.common.error.BizException;
import com.forecast.app.market.api.I18nText;
import com.forecast.app.market.api.MarketAdminApi;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 后台的市场管理（forecast S3，需求说明书第 4.1、6.2 节）：业务在 market 模块（{@link MarketAdminApi}），这里负责审计。
 * 每一步都写审计（RQ-17 第 3 条：谁、什么时候、前 → 后、原因）；高危操作（暂停 / 恢复、提前截止、取消、补充说明、改费率）原因必填，退回草稿也要写理由（第 3.2 节）。
 * 不加事务：market 模块的每个操作自己一个事务，提交以后再记"成功"的审计（失败也记，Audit 在独立事务里写）。
 */
@Service
class MarketOps {

  private final MarketAdminApi markets;
  private final Audit audit;

  MarketOps(MarketAdminApi markets, Audit audit) {
    this.markets = markets;
    this.audit = audit;
  }

  static long no(String publicNo) {
    try {
      return Long.parseLong(publicNo == null ? "" : publicNo.strip());
    } catch (NumberFormatException e) {
      throw new BizException("NOT_FOUND", "市场不存在");
    }
  }

  MarketAdminApi.Page<MarketAdminApi.Summary> list(String status, String category, Boolean submitted, String keyword, int page, int pageSize) {
    return markets.list(status, category, submitted, keyword, page, pageSize);
  }

  MarketAdminApi.Detail detail(String publicNo) {
    return markets.detail(no(publicNo)).orElseThrow(() -> new BizException("NOT_FOUND", "市场不存在"));
  }

  MarketAdminApi.Detail create(AdminContext actor, MarketAdminApi.DraftInput in) {
    return audit.run("market.create", "-", false, null, () -> {
      MarketAdminApi.Detail d = markets.createDraft(in, actor.adminId());
      return Audit.Change.of(d, null, brief(d));
    });
  }

  MarketAdminApi.Detail update(AdminContext actor, String publicNo, MarketAdminApi.DraftInput in) {
    return audit.run("market.update", publicNo, false, null, () -> {
      MarketAdminApi.Detail before = detail(publicNo);
      MarketAdminApi.Detail d = markets.updateDraft(no(publicNo), in, actor.adminId());
      return Audit.Change.of(d, brief(before), brief(d));
    });
  }

  void delete(String publicNo) {
    audit.run("market.delete", publicNo, false, null, () -> {
      MarketAdminApi.Detail before = detail(publicNo);
      markets.deleteDraft(no(publicNo));
      return Audit.Change.of(null, brief(before), Map.of("deleted", true));
    });
  }

  MarketAdminApi.Detail submit(String publicNo) {
    return audit.run("market.submit", publicNo, false, null, () -> {
      MarketAdminApi.Detail d = markets.submit(no(publicNo));
      return Audit.Change.of(d, Map.of("submitted", false), Map.of("submitted", true));
    });
  }

  /** 退回草稿：理由必填（需求第 3.2 节"退回（写理由，去掉标记）"） */
  MarketAdminApi.Detail returnDraft(String publicNo, String reason) {
    String r = Audit.requireReason(reason);
    return audit.run("market.return", publicNo, false, r, () -> {
      MarketAdminApi.Detail d = markets.returnDraft(no(publicNo));
      return Audit.Change.of(d, Map.of("submitted", true), Map.of("submitted", false));
    });
  }

  MarketAdminApi.Detail publish(AdminContext actor, String publicNo, Boolean checked) {
    return audit.run("market.publish", publicNo, false, null, () -> {
      MarketAdminApi.Detail d = markets.publish(no(publicNo), Boolean.TRUE.equals(checked), actor.adminId());
      Map<String, Object> after = new LinkedHashMap<>();
      after.put("status", d.status());
      after.put("checked", true);
      return Audit.Change.of(d, Map.of("status", "draft"), after);
    });
  }

  MarketAdminApi.Detail pause(AdminContext actor, String publicNo, String reason) {
    String r = Audit.requireReason(reason);
    return audit.run("market.pause", publicNo, true, r, () -> {
      MarketAdminApi.Detail d = markets.pause(no(publicNo), actor.adminId(), r);
      return Audit.Change.of(d, Map.of("status", "listed"), Map.of("status", d.status()));
    });
  }

  MarketAdminApi.Detail resume(AdminContext actor, String publicNo, String reason) {
    String r = Audit.requireReason(reason);
    return audit.run("market.resume", publicNo, true, r, () -> {
      MarketAdminApi.Detail d = markets.resume(no(publicNo), actor.adminId(), r);
      return Audit.Change.of(d, Map.of("status", "paused"), Map.of("status", d.status()));
    });
  }

  MarketAdminApi.Detail cancel(AdminContext actor, String publicNo, String reason) {
    String r = Audit.requireReason(reason);
    return audit.run("market.cancel", publicNo, true, r, () -> {
      MarketAdminApi.Detail before = detail(publicNo);
      MarketAdminApi.Detail d = markets.cancel(no(publicNo), actor.adminId(), r);
      return Audit.Change.of(d, Map.of("status", before.status()), Map.of("status", d.status()));
    });
  }

  MarketAdminApi.Detail clarify(AdminContext actor, String publicNo, I18nText text, String reason) {
    String r = Audit.requireReason(reason);
    return audit.run("market.clarify", publicNo, true, r, () -> {
      MarketAdminApi.Detail d = markets.clarify(no(publicNo), text, actor.adminId());
      Map<String, Object> after = new LinkedHashMap<>();
      after.put("en", text == null ? null : text.en());
      after.put("zhCN", text == null ? null : text.zhCN());
      after.put("zhTW", text == null ? null : text.zhTW());
      return Audit.Change.of(d, null, Map.of("clarification", after));
    });
  }

  MarketAdminApi.Detail advanceClose(AdminContext actor, String publicNo, String closeAt, String reason) {
    String r = Audit.requireReason(reason);
    return audit.run("market.advance_close", publicNo, true, r, () -> {
      MarketAdminApi.Detail before = detail(publicNo);
      MarketAdminApi.Detail d = markets.advanceClose(no(publicNo), closeAt, actor.adminId(), r);
      return Audit.Change.of(d, Map.of("closeAt", before.closeAt()), Map.of("closeAt", d.closeAt()));
    });
  }

  /** 改本市场费率（fee:edit，高危）：审计写"前 → 后"（需求第 3.8 节第 3 条） */
  MarketAdminApi.FeeChange changeFee(AdminContext actor, String publicNo, String feeRate, String reason) {
    String r = Audit.requireReason(reason);
    return audit.run("market.fee", publicNo, true, r, () -> {
      MarketAdminApi.FeeChange c = markets.changeFee(no(publicNo), feeRate, actor.adminId(), r);
      Map<String, Object> after = new LinkedHashMap<>();
      after.put("feeRate", c.newRate());
      after.put("notified", c.notified());
      return Audit.Change.of(c, Map.of("feeRate", c.oldRate()), after);
    });
  }

  /** 审计里记的摘要（不记规则全文） */
  private static Map<String, Object> brief(MarketAdminApi.Detail d) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("publicNo", d.publicNo());
    m.put("category", d.category());
    m.put("status", d.status());
    m.put("titleZhCN", d.title().zhCN());
    m.put("openAt", d.openAt());
    m.put("closeAt", d.closeAt());
    m.put("eventStartAt", d.eventStartAt());
    m.put("eventEndAt", d.eventEndAt());
    m.put("feeRate", d.feeRate());
    m.put("options", d.options().size());
    return m;
  }
}
