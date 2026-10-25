package com.forecast.app.admin.ops;

import com.forecast.app.admin.security.AdminContext;
import com.forecast.app.admin.security.Audit;
import com.forecast.app.common.error.BizException;
import com.forecast.app.resolve.api.SportsAdminApi;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 后台的体育赛程（forecast S8，需求说明书 v1.3 第 2.2、4.2 节）：业务在 resolve（{@link SportsAdminApi}），这里负责审计。
 * <ul>
 *   <li>同步、找候选、确认 / 取消对应、生成草稿：{@code market:edit}（运营；需求第 2.1 节"每天从赛程生成当天足球 / NBA 市场的草稿"）。写审计，原因可选。
 *       生成的草稿照常要"提交上架 → 有 market:publish 的人检查后上架"。</li>
 *   <li>改规则文本模板：{@code market:autogen}（★ 高危，原因必填），和加密的规则文本模板同一个权限点。</li>
 * </ul>
 */
@Service
class SportsOps {

  private final SportsAdminApi sports;
  private final Audit audit;

  SportsOps(SportsAdminApi sports, Audit audit) {
    this.sports = sports;
    this.audit = audit;
  }

  static long no(String s) {
    return AutogenOps.no(s, "比赛");
  }

  SportsAdminApi.Page<SportsAdminApi.Fixture> fixtures(String sport, String mapping, String from, String to, int page, int pageSize) {
    return sports.fixtures(sport, mapping, from, to, page, pageSize);
  }

  SportsAdminApi.Fixture fixture(String fixtureNo) {
    return sports.fixture(no(fixtureNo)).orElseThrow(() -> new BizException("NOT_FOUND", "比赛不存在"));
  }

  List<SportsAdminApi.SyncResult> sync(AdminContext actor, String sport) {
    return audit.run("sports.sync", sport == null || sport.isBlank() ? "ALL" : sport.strip(), false, null, () -> {
      List<SportsAdminApi.SyncResult> r = sports.sync(sport);
      Map<String, Object> after = new LinkedHashMap<>();
      for (SportsAdminApi.SyncResult x : r) after.put(x.sport(), x.outcome() + " " + x.fetched() + "/" + x.created() + "/" + x.updated());
      return Audit.Change.of(r, null, after);
    });
  }

  SportsAdminApi.Fixture candidates(AdminContext actor, String fixtureNo, String ref) {
    return audit.run("sports.candidates", fixtureNo, false, null, () -> {
      SportsAdminApi.Fixture f = sports.searchCandidates(no(fixtureNo), ref);
      Map<String, Object> after = new LinkedHashMap<>();
      after.put("candidates", f.candidates().stream().map(c -> c.ref() + "@" + c.score()).toList());
      if (ref != null && !ref.isBlank()) after.put("ref", ref.strip());
      return Audit.Change.of(f, null, after);
    });
  }

  SportsAdminApi.Fixture confirm(AdminContext actor, String fixtureNo, String ref, String reason) {
    String r = optionalReason(reason);
    return audit.run("sports.mapping.confirm", fixtureNo, false, r, () -> {
      SportsAdminApi.Fixture before = fixture(fixtureNo);
      SportsAdminApi.Fixture f = sports.confirmMapping(no(fixtureNo), ref, actor.adminId());
      return Audit.Change.of(f, mapping(before), mapping(f));
    });
  }

  SportsAdminApi.Fixture clear(AdminContext actor, String fixtureNo, String reason) {
    String r = optionalReason(reason);
    return audit.run("sports.mapping.clear", fixtureNo, false, r, () -> {
      SportsAdminApi.Fixture before = fixture(fixtureNo);
      SportsAdminApi.Fixture f = sports.clearMapping(no(fixtureNo), actor.adminId());
      return Audit.Change.of(f, mapping(before), mapping(f));
    });
  }

  SportsAdminApi.Generated generate(AdminContext actor, String fixtureNo, List<SportsAdminApi.PlayInput> plays) {
    return audit.run("sports.drafts.generate", fixtureNo, false, null, () -> {
      SportsAdminApi.Generated g = sports.generate(no(fixtureNo), plays, actor.adminId());
      Map<String, Object> after = new LinkedHashMap<>();
      after.put("markets", g.created().stream().map(m -> m.marketNo() + " " + m.play() + (m.line() == null ? "" : " " + m.line())).toList());
      after.put("warnings", g.warnings());
      return Audit.Change.of(g, null, after);
    });
  }

  List<SportsAdminApi.Template> templates() {
    return sports.templates();
  }

  SportsAdminApi.Template updateTemplate(AdminContext actor, String kind, SportsAdminApi.TemplateInput in, String reason) {
    String r = Audit.requireReason(reason);
    return audit.run("sports.template.update", kind, true, r, () -> {
      SportsAdminApi.Template before = sports.templates().stream().filter(t -> t.kind().equalsIgnoreCase(kind == null ? "" : kind.strip())).findFirst()
          .orElseThrow(() -> new BizException("NOT_FOUND", "没有这个玩法的模板"));
      SportsAdminApi.Template t = sports.updateTemplate(kind, in, actor.adminId());
      return Audit.Change.of(t, Map.of("version", before.version(), "titleZhCN", before.title().zhCN()), Map.of("version", t.version(), "titleZhCN",
          t.title().zhCN()));
    });
  }

  private static String optionalReason(String reason) {
    if (reason == null || reason.isBlank()) return null;
    if (reason.strip().length() > 500) {
      throw new com.forecast.app.common.error.ValidationException(List.of(new com.forecast.app.common.error.ValidationException.Field("reason", "tooLong")));
    }
    return reason.strip();
  }

  private static Map<String, Object> mapping(SportsAdminApi.Fixture f) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("backupRef", f.mapping() == null ? null : f.mapping().ref());
    m.put("swapped", f.mapping() != null && f.mapping().swapped());
    return m;
  }
}
