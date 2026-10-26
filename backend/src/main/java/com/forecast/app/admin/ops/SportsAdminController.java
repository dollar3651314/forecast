package com.forecast.app.admin.ops;

import com.forecast.app.admin.security.AdminContext;
import com.forecast.app.admin.security.Permission;
import com.forecast.app.admin.security.RequiresPermission;
import com.forecast.app.market.api.I18nText;
import com.forecast.app.resolve.api.SportsAdminApi;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后台"从赛程生成"接口（forecast S8，需求说明书 v1.3 第 4.2、6.2 节；页面在原型确认后做，S9）。字段见 {@link SportsAdminApi}；每个接口声明权限点（项目地图约定 12），
 * 审计在 {@link SportsOps}。
 */
@RestController
class SportsAdminController {

  record SyncBody(String sport) {}

  record CandidatesBody(String ref) {}

  record MappingBody(String ref, String reason) {}

  record ReasonBody(String reason) {}

  record DraftsBody(List<SportsAdminApi.PlayInput> plays) {}

  record TemplateBody(String name, I18nText title, I18nText rules, String sourcePrimary, String sourceBackup, List<SportsAdminApi.TemplateOption> options,
      String reason) {}

  private final SportsOps ops;

  SportsAdminController(SportsOps ops) {
    this.ops = ops;
  }

  /** 赛程（默认未来 7 天，按开赛时间）：sport = FOOTBALL / NBA，mapping = confirmed / unconfirmed */
  @RequiresPermission(Permission.MARKET_VIEW)
  @GetMapping("/api/admin/sports/fixtures")
  SportsAdminApi.Page<SportsAdminApi.Fixture> fixtures(@RequestParam(required = false) String sport, @RequestParam(required = false) String mapping,
      @RequestParam(required = false) String from, @RequestParam(required = false) String to, @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "50") int pageSize) {
    return ops.fixtures(sport, mapping, from, to, page, pageSize);
  }

  @RequiresPermission(Permission.MARKET_VIEW)
  @GetMapping("/api/admin/sports/fixtures/{fixtureNo}")
  SportsAdminApi.Fixture fixture(@PathVariable String fixtureNo) {
    return ops.fixture(fixtureNo);
  }

  /** 立即同步（不传 sport = 足球和 NBA）；来源未开通的返回 disabled */
  @RequiresPermission(Permission.MARKET_EDIT)
  @PostMapping("/api/admin/sports/fixtures/sync")
  List<SportsAdminApi.SyncResult> sync(@RequestBody(required = false) SyncBody body) {
    return ops.sync(AdminContext.current(), body == null ? null : body.sport());
  }

  /** 在备源里找候选；{"ref": "…"} 只查这个编号（运营自己在 TheSportsDB 上找到的）并加进候选 */
  @RequiresPermission(Permission.MARKET_EDIT)
  @PostMapping("/api/admin/sports/fixtures/{fixtureNo}/candidates")
  SportsAdminApi.Fixture candidates(@PathVariable String fixtureNo, @RequestBody(required = false) CandidatesBody body) {
    return ops.candidates(AdminContext.current(), fixtureNo, body == null ? null : body.ref());
  }

  /** 确认对应：ref 必须是候选里的；开赛以后不能改（409 FIXTURE_STARTED） */
  @RequiresPermission(Permission.MARKET_EDIT)
  @PostMapping("/api/admin/sports/fixtures/{fixtureNo}/mapping")
  SportsAdminApi.Fixture confirm(@PathVariable String fixtureNo, @RequestBody(required = false) MappingBody body) {
    return ops.confirm(AdminContext.current(), fixtureNo, body == null ? null : body.ref(), body == null ? null : body.reason());
  }

  /** 取消对应（开赛以前） */
  @RequiresPermission(Permission.MARKET_EDIT)
  @DeleteMapping("/api/admin/sports/fixtures/{fixtureNo}/mapping")
  SportsAdminApi.Fixture clear(@PathVariable String fixtureNo, @RequestBody(required = false) ReasonBody body) {
    return ops.clear(AdminContext.current(), fixtureNo, body == null ? null : body.reason());
  }

  /** 一键生成草稿：{"plays": [{"play": "FOOTBALL_OU", "line": "2.5"}, …]}；之后照常提交上架、由有 market:publish 的人上架 */
  @RequiresPermission(Permission.MARKET_EDIT)
  @PostMapping("/api/admin/sports/fixtures/{fixtureNo}/drafts")
  SportsAdminApi.Generated drafts(@PathVariable String fixtureNo, @RequestBody(required = false) DraftsBody body) {
    return ops.generate(AdminContext.current(), fixtureNo, body == null ? null : body.plays());
  }

  @RequiresPermission(Permission.MARKET_VIEW)
  @GetMapping("/api/admin/sports/templates")
  List<SportsAdminApi.Template> templates() {
    return ops.templates();
  }

  /** 改规则文本模板（高危，原因必填）：只影响之后生成的草稿 */
  @RequiresPermission(Permission.MARKET_AUTOGEN)
  @PutMapping("/api/admin/sports/templates/{kind}")
  SportsAdminApi.Template updateTemplate(@PathVariable String kind, @RequestBody(required = false) TemplateBody body) {
    TemplateBody b = body == null ? new TemplateBody(null, null, null, null, null, null, null) : body;
    return ops.updateTemplate(AdminContext.current(), kind, new SportsAdminApi.TemplateInput(b.name(), b.title(), b.rules(), b.sourcePrimary(), b.sourceBackup(),
        b.options()), b.reason());
  }
}
