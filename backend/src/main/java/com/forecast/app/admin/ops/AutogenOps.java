package com.forecast.app.admin.ops;

import com.forecast.app.admin.security.AdminContext;
import com.forecast.app.admin.security.Audit;
import com.forecast.app.common.error.BizException;
import com.forecast.app.common.error.ValidationException;
import com.forecast.app.market.api.MarketAutogenAdminApi;
import com.forecast.app.resolve.api.ResolveAdminApi;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 后台的加密市场自动生成和自动裁决（forecast S6，需求说明书第 2.2、4.2、4.3 节）：业务在 market（{@link MarketAutogenAdminApi}）、resolve（{@link ResolveAdminApi}），
 * 这里负责审计。
 * <ul>
 *   <li>新建、修改、关闭自动生成模板，修改规则文本模板：{@code market:autogen}（★ 高危，原因必填）。</li>
 *   <li>启用：{@code market:publish}，必须勾选"已按第 4.1 节清单检查"（需求第 4.2 节"模板启用前由有 market:publish 的人检查一次"）。</li>
 * </ul>
 * 不加事务：market 的每个操作自己一个事务，提交以后再记"成功"的审计（失败也记，Audit 在独立事务里写）。
 */
@Service
class AutogenOps {

  private final MarketAutogenAdminApi autogen;
  private final ResolveAdminApi resolve;
  private final Audit audit;

  AutogenOps(MarketAutogenAdminApi autogen, ResolveAdminApi resolve, Audit audit) {
    this.autogen = autogen;
    this.resolve = resolve;
    this.audit = audit;
  }

  static long no(String s, String what) {
    try {
      return Long.parseLong(s == null ? "" : s.strip());
    } catch (NumberFormatException e) {
      throw new BizException("NOT_FOUND", what + "不存在");
    }
  }

  List<MarketAutogenAdminApi.Autogen> list() {
    return autogen.list();
  }

  MarketAutogenAdminApi.Autogen detail(String autogenNo) {
    return autogen.detail(no(autogenNo, "自动生成模板")).orElseThrow(() -> new BizException("NOT_FOUND", "自动生成模板不存在"));
  }

  MarketAutogenAdminApi.Autogen create(AdminContext actor, MarketAutogenAdminApi.AutogenInput in, String reason) {
    String r = Audit.requireReason(reason);
    return audit.run("market.autogen.create", "-", true, r, () -> {
      MarketAutogenAdminApi.Autogen a = autogen.create(in, actor.adminId());
      return Audit.Change.of(a, null, brief(a));
    });
  }

  MarketAutogenAdminApi.Autogen update(AdminContext actor, String autogenNo, MarketAutogenAdminApi.AutogenInput in, String reason) {
    String r = Audit.requireReason(reason);
    return audit.run("market.autogen.update", autogenNo, true, r, () -> {
      MarketAutogenAdminApi.Autogen before = detail(autogenNo);
      MarketAutogenAdminApi.Autogen a = autogen.update(no(autogenNo, "自动生成模板"), in, actor.adminId());
      return Audit.Change.of(a, brief(before), brief(a));
    });
  }

  /** 启用（market:publish）：勾选确认必填；原因可选（写进审计） */
  MarketAutogenAdminApi.Autogen enable(AdminContext actor, String autogenNo, Boolean checked, String reason) {
    String r = reason == null || reason.isBlank() ? null : reason.strip();
    if (r != null && r.length() > 500) throw new ValidationException(List.of(new ValidationException.Field("reason", "tooLong")));
    return audit.run("market.autogen.enable", autogenNo, false, r, () -> {
      MarketAutogenAdminApi.Autogen a = autogen.enable(no(autogenNo, "自动生成模板"), Boolean.TRUE.equals(checked), actor.adminId());
      Map<String, Object> after = brief(a);
      after.put("checked", true);
      after.put("checkedTemplateVersion", a.checkedTemplateVersion());
      return Audit.Change.of(a, Map.of("enabled", false), after);
    });
  }

  MarketAutogenAdminApi.Autogen disable(AdminContext actor, String autogenNo, String reason) {
    String r = Audit.requireReason(reason);
    return audit.run("market.autogen.disable", autogenNo, true, r, () -> {
      MarketAutogenAdminApi.Autogen a = autogen.disable(no(autogenNo, "自动生成模板"), actor.adminId());
      return Audit.Change.of(a, Map.of("enabled", true), Map.of("enabled", a.enabled()));
    });
  }

  List<MarketAutogenAdminApi.Template> templates() {
    return autogen.templates();
  }

  MarketAutogenAdminApi.Template template(String templateNo) {
    return autogen.template(no(templateNo, "规则文本模板")).orElseThrow(() -> new BizException("NOT_FOUND", "规则文本模板不存在"));
  }

  MarketAutogenAdminApi.Template updateTemplate(AdminContext actor, String templateNo, MarketAutogenAdminApi.TemplateInput in, String reason) {
    String r = Audit.requireReason(reason);
    return audit.run("market.template.update", templateNo, true, r, () -> {
      MarketAutogenAdminApi.Template before = template(templateNo);
      MarketAutogenAdminApi.Template t = autogen.updateTemplate(no(templateNo, "规则文本模板"), in, actor.adminId());
      return Audit.Change.of(t, Map.of("version", before.version(), "titleZhCN", before.title().zhCN()), Map.of("version", t.version(), "titleZhCN",
          t.title().zhCN()));
    });
  }

  ResolveAdminApi.Evidence evidence(String marketNo) {
    return resolve.evidence(no(marketNo, "市场")).orElseThrow(() -> new BizException("NOT_FOUND", "市场不存在"));
  }

  ResolveAdminApi.Page<ResolveAdminApi.Task> manual(int page, int pageSize) {
    return resolve.manual(page, pageSize);
  }

  /** 审计里记的摘要 */
  private static Map<String, Object> brief(MarketAutogenAdminApi.Autogen a) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("autogenNo", a.autogenNo());
    m.put("symbol", a.symbol());
    m.put("period", a.period());
    m.put("mode", a.mode());
    m.put("slots", new ArrayList<>(a.slots()));
    m.put("rollingCount", a.rollingCount());
    m.put("generateAheadMinutes", a.generateAheadMinutes());
    m.put("templateNo", a.templateNo());
    m.put("userCap", a.userCap());
    m.put("enabled", a.enabled());
    return m;
  }
}
