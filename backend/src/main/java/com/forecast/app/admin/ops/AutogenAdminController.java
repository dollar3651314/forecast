package com.forecast.app.admin.ops;

import com.forecast.app.admin.security.AdminContext;
import com.forecast.app.admin.security.Permission;
import com.forecast.app.admin.security.RequiresPermission;
import com.forecast.app.market.api.I18nText;
import com.forecast.app.market.api.MarketAutogenAdminApi;
import com.forecast.app.resolve.api.ResolveAdminApi;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后台"加密自动生成"和"自动裁决"接口（forecast S6，需求说明书第 4.2、4.3、6.2 节；页面在原型确认后做）。字段见 {@link MarketAutogenAdminApi}、{@link ResolveAdminApi}；
 * 每个接口声明权限点（项目地图约定 12），审计在 {@link AutogenOps}。
 */
@RestController
class AutogenAdminController {

  /** 新建、修改：模板的字段 + 原因（高危） */
  record AutogenBody(String symbol, String period, String mode, List<String> slots, Integer rollingCount, Integer generateAheadMinutes, String templateNo,
      String userCap, String reason) {

    MarketAutogenAdminApi.AutogenInput input() {
      return new MarketAutogenAdminApi.AutogenInput(symbol, period, mode, slots, rollingCount, generateAheadMinutes, templateNo, userCap);
    }
  }

  record EnableBody(Boolean checked, String reason) {}

  record ReasonBody(String reason) {}

  record TemplateBody(String name, I18nText title, I18nText rules, String sourcePrimary, String sourceBackup, List<MarketAutogenAdminApi.TemplateOption> options,
      String reason) {}

  private final AutogenOps ops;

  AutogenAdminController(AutogenOps ops) {
    this.ops = ops;
  }

  @RequiresPermission(Permission.MARKET_VIEW)
  @GetMapping("/api/admin/market-autogens")
  List<MarketAutogenAdminApi.Autogen> list() {
    return ops.list();
  }

  /** 详情：配置、接下来的窗口（已生成的带市场编号）、按下一个窗口渲染的样子、缺的必填项 */
  @RequiresPermission(Permission.MARKET_VIEW)
  @GetMapping("/api/admin/market-autogens/{autogenNo}")
  MarketAutogenAdminApi.Autogen detail(@PathVariable String autogenNo) {
    return ops.detail(autogenNo);
  }

  @RequiresPermission(Permission.MARKET_AUTOGEN)
  @PostMapping("/api/admin/market-autogens")
  MarketAutogenAdminApi.Autogen create(@RequestBody(required = false) AutogenBody body) {
    AutogenBody b = body == null ? new AutogenBody(null, null, null, null, null, null, null, null, null) : body;
    return ops.create(AdminContext.current(), b.input(), b.reason());
  }

  /** 修改（只能在关闭时，启用中 409 AUTOGEN_ENABLED） */
  @RequiresPermission(Permission.MARKET_AUTOGEN)
  @PutMapping("/api/admin/market-autogens/{autogenNo}")
  MarketAutogenAdminApi.Autogen update(@PathVariable String autogenNo, @RequestBody(required = false) AutogenBody body) {
    AutogenBody b = body == null ? new AutogenBody(null, null, null, null, null, null, null, null, null) : body;
    return ops.update(AdminContext.current(), autogenNo, b.input(), b.reason());
  }

  /** 启用：{"checked": true}（已按第 4.1 节清单检查），要 market:publish */
  @RequiresPermission(Permission.MARKET_PUBLISH)
  @PostMapping("/api/admin/market-autogens/{autogenNo}/enable")
  MarketAutogenAdminApi.Autogen enable(@PathVariable String autogenNo, @RequestBody(required = false) EnableBody body) {
    return ops.enable(AdminContext.current(), autogenNo, body == null ? null : body.checked(), body == null ? null : body.reason());
  }

  /** 关闭：不再生成新的，已生成的照常 */
  @RequiresPermission(Permission.MARKET_AUTOGEN)
  @PostMapping("/api/admin/market-autogens/{autogenNo}/disable")
  MarketAutogenAdminApi.Autogen disable(@PathVariable String autogenNo, @RequestBody(required = false) ReasonBody body) {
    return ops.disable(AdminContext.current(), autogenNo, body == null ? null : body.reason());
  }

  @RequiresPermission(Permission.MARKET_VIEW)
  @GetMapping("/api/admin/market-templates")
  List<MarketAutogenAdminApi.Template> templates() {
    return ops.templates();
  }

  @RequiresPermission(Permission.MARKET_VIEW)
  @GetMapping("/api/admin/market-templates/{templateNo}")
  MarketAutogenAdminApi.Template template(@PathVariable String templateNo) {
    return ops.template(templateNo);
  }

  /** 修改规则文本模板（有启用中的自动生成在用时 409 TEMPLATE_IN_USE） */
  @RequiresPermission(Permission.MARKET_AUTOGEN)
  @PutMapping("/api/admin/market-templates/{templateNo}")
  MarketAutogenAdminApi.Template updateTemplate(@PathVariable String templateNo, @RequestBody(required = false) TemplateBody body) {
    TemplateBody b = body == null ? new TemplateBody(null, null, null, null, null, null, null) : body;
    return ops.updateTemplate(AdminContext.current(), templateNo,
        new MarketAutogenAdminApi.TemplateInput(b.name(), b.title(), b.rules(), b.sourcePrimary(), b.sourceBackup(), b.options()), b.reason());
  }

  /** 一个市场的自动裁决依据：进度、裁决记录、数据源原始返回（含 sha256） */
  @RequiresPermission(Permission.MARKET_VIEW)
  @GetMapping("/api/admin/markets/{publicNo}/resolution")
  ResolveAdminApi.Evidence resolution(@PathVariable String publicNo) {
    return ops.evidence(publicNo);
  }

  /** 自动裁决失败转人工的市场（S7 的裁决台"自动裁决失败"在这上面做） */
  @RequiresPermission(Permission.RESOLVE_PROPOSE)
  @GetMapping("/api/admin/resolve/auto-failed")
  ResolveAdminApi.Page<ResolveAdminApi.Task> autoFailed(@RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize) {
    return ops.manual(page, pageSize);
  }
}
