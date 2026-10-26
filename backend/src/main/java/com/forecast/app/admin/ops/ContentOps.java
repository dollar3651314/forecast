package com.forecast.app.admin.ops;

import com.forecast.app.admin.security.AdminContext;
import com.forecast.app.admin.security.Audit;
import com.forecast.app.common.error.BizException;
import com.forecast.app.common.error.ValidationException;
import com.forecast.app.content.api.ContentAdminApi;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 后台"内容中心"（forecast S15；需求第 6.2 节、自动化需求第 7 节）：业务在 content 模块（{@link ContentAdminApi}），这里负责审计和高危确认。
 * content:publish 的操作（发布、改设置和词表、紧急停止、页面显示 / 隐藏）是高危：原因必填、勾选确认（C73：对外发布由 Amos 决定）。
 */
@Service
class ContentOps {

  private final ContentAdminApi content;
  private final Audit audit;

  ContentOps(ContentAdminApi content, Audit audit) {
    this.content = content;
    this.audit = audit;
  }

  static long no(String s, String what) {
    try {
      return Long.parseLong(s == null ? "" : s.strip());
    } catch (NumberFormatException e) {
      throw new BizException("NOT_FOUND", what + "不存在");
    }
  }

  /** 高危确认：原因必填、勾选确认 */
  static String highRisk(String reason, Boolean checked) {
    List<ValidationException.Field> errs = new ArrayList<>();
    try {
      Audit.requireReason(reason);
    } catch (ValidationException e) {
      errs.addAll(e.fields());
    }
    if (!Boolean.TRUE.equals(checked)) errs.add(new ValidationException.Field("checked", "required"));
    if (!errs.isEmpty()) throw new ValidationException(errs);
    return reason.strip();
  }

  // ---------- 草稿箱 ----------

  ContentAdminApi.Page<ContentAdminApi.Draft> drafts(String status, String kind, String lang, int page, int pageSize) {
    return content.drafts(status, kind, lang, page, pageSize);
  }

  List<ContentAdminApi.Draft> group(String draftNo) {
    return content.group(no(draftNo, "草稿"));
  }

  List<ContentAdminApi.Draft> generate(AdminContext actor, ContentAdminApi.GenerateInput in) {
    return audit.run("content.generate", in == null ? "-" : String.valueOf(in.kind()), false, null, () -> {
      List<ContentAdminApi.Draft> d = content.generate(in, actor.adminId());
      Map<String, Object> after = new LinkedHashMap<>();
      after.put("kind", in.kind());
      after.put("marketNo", in.marketNo());
      after.put("pageSlug", in.pageSlug());
      after.put("topic", in.topic());
      after.put("drafts", d.stream().map(ContentAdminApi.Draft::draftNo).toList());
      return Audit.Change.of(d, null, after);
    });
  }

  ContentAdminApi.Draft edit(AdminContext actor, String draftNo, ContentAdminApi.EditInput in) {
    return audit.run("content.edit", draftNo, false, null, () -> {
      ContentAdminApi.Draft d = content.edit(no(draftNo, "草稿"), in, actor.adminId());
      return Audit.Change.of(d, null, Map.of("checkOk", d.checkOk(), "version", d.version()));
    });
  }

  ContentAdminApi.Draft publish(AdminContext actor, String draftNo, String reason, Boolean checked) {
    String r = highRisk(reason, checked);
    return audit.run("content.publish", draftNo, true, r, () -> {
      ContentAdminApi.Draft d = content.publish(no(draftNo, "草稿"), actor.adminId());
      Map<String, Object> after = new LinkedHashMap<>();
      after.put("status", d.status());
      after.put("kind", d.kind());
      after.put("lang", d.lang());
      if (d.siteUrl() != null) after.put("siteUrl", d.siteUrl());
      return Audit.Change.of(d, Map.of("status", "PENDING"), after);
    });
  }

  ContentAdminApi.Draft reject(AdminContext actor, String draftNo, String reason) {
    String r = Audit.requireReason(reason);
    return audit.run("content.reject", draftNo, false, r, () -> {
      ContentAdminApi.Draft d = content.reject(no(draftNo, "草稿"), r, actor.adminId());
      return Audit.Change.of(d, null, Map.of("status", d.status()));
    });
  }

  ContentAdminApi.Draft retry(AdminContext actor, String draftNo) {
    return audit.run("content.retry", draftNo, false, null, () -> {
      ContentAdminApi.Draft d = content.retry(no(draftNo, "草稿"), actor.adminId());
      return Audit.Change.of(d, null, Map.of("status", d.status()));
    });
  }

  ContentAdminApi.Draft copied(AdminContext actor, String draftNo) {
    return audit.run("content.copy", draftNo, false, null, () -> {
      ContentAdminApi.Draft d = content.copied(no(draftNo, "草稿"), actor.adminId());
      return Audit.Change.of(d, null, Map.of("kind", d.kind(), "lang", d.lang()));
    });
  }

  // ---------- 用量和设置 ----------

  ContentAdminApi.Status status() {
    return content.status();
  }

  ContentAdminApi.Status settings(AdminContext actor, Map<String, String> values, String reason, Boolean checked) {
    String r = highRisk(reason, checked);
    ContentAdminApi.Status before = content.status();
    return audit.run("content.settings", "-", true, r, () -> {
      ContentAdminApi.Status s = content.updateSettings(values, actor.adminId());
      Map<String, Object> b = new LinkedHashMap<>();
      Map<String, Object> a = new LinkedHashMap<>();
      if (values != null) {
        for (String k : values.keySet()) {
          b.put(k, before.settings().get(k));
          a.put(k, s.settings().get(k));
        }
      }
      return Audit.Change.of(s, b, a);
    });
  }

  ContentAdminApi.Status stop(AdminContext actor, Boolean stopped, String reason, Boolean checked) {
    String r = highRisk(reason, checked);
    if (stopped == null) throw new ValidationException(List.of(new ValidationException.Field("stopped", "required")));
    return audit.run(stopped ? "content.stop" : "content.resume", "-", true, r, () -> {
      ContentAdminApi.Status s = content.setStopped(stopped, actor.adminId());
      return Audit.Change.of(s, Map.of("stopped", !stopped), Map.of("stopped", s.stopped()));
    });
  }

  List<ContentAdminApi.Word> words(String kind) {
    return content.words(kind);
  }

  ContentAdminApi.Word addWord(AdminContext actor, String kind, String word, String reason, Boolean checked) {
    String r = highRisk(reason, checked);
    return audit.run("content.word.add", String.valueOf(kind), true, r, () -> {
      ContentAdminApi.Word w = content.addWord(kind, word, actor.adminId());
      return Audit.Change.of(w, null, Map.of("kind", w.kind(), "word", w.word()));
    });
  }

  ContentAdminApi.Word deleteWord(String id, String reason, Boolean checked) {
    String r = highRisk(reason, checked);
    return audit.run("content.word.delete", id, true, r, () -> {
      ContentAdminApi.Word w = content.deleteWord(no(id, "词"));
      return Audit.Change.of(w, Map.of("kind", w.kind(), "word", w.word()), Map.of("deleted", true));
    });
  }

  // ---------- 事件候选 ----------

  ContentAdminApi.Candidates candidates() {
    return content.candidates();
  }

  List<ContentAdminApi.Econ> econ() {
    return content.econ();
  }

  ContentAdminApi.Econ addEcon(AdminContext actor, ContentAdminApi.EconInput in) {
    return audit.run("content.econ.add", "-", false, null, () -> {
      ContentAdminApi.Econ e = content.addEcon(in, actor.adminId());
      return Audit.Change.of(e, null, Map.of("id", e.id(), "kind", e.kind(), "period", e.period(), "releaseAt", e.releaseAt()));
    });
  }

  ContentAdminApi.Econ updateEcon(AdminContext actor, String id, ContentAdminApi.EconInput in) {
    return audit.run("content.econ.update", id, false, null, () -> {
      ContentAdminApi.Econ e = content.updateEcon(no(id, "经济日历"), in, actor.adminId());
      return Audit.Change.of(e, null, Map.of("kind", e.kind(), "period", e.period(), "releaseAt", e.releaseAt()));
    });
  }

  ContentAdminApi.Econ cancelEcon(AdminContext actor, String id) {
    return audit.run("content.econ.cancel", id, false, null, () -> {
      ContentAdminApi.Econ e = content.cancelEcon(no(id, "经济日历"), actor.adminId());
      return Audit.Change.of(e, null, Map.of("status", e.status()));
    });
  }

  ContentAdminApi.Econ draftEcon(AdminContext actor, String id) {
    return audit.run("content.econ.draft", id, false, null, () -> {
      ContentAdminApi.Econ e = content.draftEcon(no(id, "经济日历"), actor.adminId());
      Map<String, Object> after = new LinkedHashMap<>();
      after.put("status", e.status());
      after.put("marketNo", e.marketNo());
      return Audit.Change.of(e, null, after);
    });
  }

  // ---------- 模板 ----------

  List<ContentAdminApi.Template> templates() {
    return content.templates();
  }

  ContentAdminApi.Template updateTemplate(AdminContext actor, String code, ContentAdminApi.TemplateInput in) {
    return audit.run("content.template.update", code, false, null, () -> {
      ContentAdminApi.Template t = content.updateTemplate(code, in, actor.adminId());
      return Audit.Change.of(t, null, Map.of("version", t.version()));
    });
  }

  // ---------- 页面 ----------

  ContentAdminApi.Pages pages() {
    return content.pages();
  }

  ContentAdminApi.PageRow refreshBtc(AdminContext actor) {
    return audit.run("content.page.refresh", "btc-hourly", false, null, () -> {
      ContentAdminApi.PageRow p = content.refreshBtc(actor.adminId());
      return Audit.Change.of(p, null, Map.of("dataAt", String.valueOf(p.dataAt())));
    });
  }

  ContentAdminApi.PageRow visibility(AdminContext actor, String slug, Boolean visible, String reason, Boolean checked) {
    String r = highRisk(reason, checked);
    if (visible == null) throw new ValidationException(List.of(new ValidationException.Field("visible", "required")));
    return audit.run("content.page.visibility", slug, true, r, () -> {
      ContentAdminApi.PageRow p = content.setPageVisible(slug, visible, actor.adminId());
      return Audit.Change.of(p, null, Map.of("status", p.status()));
    });
  }

  // ---------- 工具 ----------

  ContentAdminApi.ToolResult dataCard(String kind, String marketNo, String lang) {
    return content.dataCard(kind, marketNo, lang);
  }

  ContentAdminApi.ToolResult compare(AdminContext actor, String marketNo, String viewpoint, String polymarketSlug, String lang) {
    return audit.run("content.tool.compare", String.valueOf(marketNo), false, null, () -> {
      ContentAdminApi.ToolResult t = content.compare(marketNo, viewpoint, polymarketSlug, lang, actor.adminId());
      return Audit.Change.of(t, null, Map.of("replies", t.replies().size()));
    });
  }

  // ---------- 日志 ----------

  ContentAdminApi.Page<ContentAdminApi.LogRow> logs(String from, String to, String action, int page, int pageSize) {
    return content.logs(from, to, action, page, pageSize);
  }

  String exportLogs(String from, String to, String action) {
    return audit.run("content.logs.export", "-", false, null, () -> {
      String csv = content.logsCsv(from, to, action);
      Map<String, Object> after = new LinkedHashMap<>();
      after.put("from", from);
      after.put("to", to);
      after.put("action", action);
      after.put("lines", csv.chars().filter(c -> c == '\n').count());
      return Audit.Change.of(csv, null, after);
    });
  }
}
