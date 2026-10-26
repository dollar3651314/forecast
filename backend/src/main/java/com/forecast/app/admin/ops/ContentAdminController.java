package com.forecast.app.admin.ops;

import com.forecast.app.admin.security.AdminContext;
import com.forecast.app.admin.security.Permission;
import com.forecast.app.admin.security.RequiresPermission;
import com.forecast.app.content.api.ContentAdminApi;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后台"内容中心"接口（forecast S15；需求第 6.2 节、自动化需求第 7 节）。字段见 {@link ContentAdminApi}；审计和高危确认在 {@link ContentOps}。
 * 看：content:view；改草稿、生成、经济日历、模板、工具：content:edit；发布、设置、词表、紧急停止、页面显示：content:publish（高危）。
 * <b>没有任何把内容发到外部渠道的接口</b>：X / Telegram 帖子审核后由人复制手动发，回复草稿只能复制（AC-110；SQ-22 Amos 答复后只有开奖卡自动发到 Telegram 频道，在 S16 的 TelegramAdminController / growth.telegram，MF-7）。
 */
@RestController
class ContentAdminController {

  record HighRiskBody(String reason, Boolean checked) {}

  record SettingsBody(Map<String, String> values, String reason, Boolean checked) {}

  record StopBody(Boolean stopped, String reason, Boolean checked) {}

  record WordBody(String kind, String word, String reason, Boolean checked) {}

  record RejectBody(String reason) {}

  record VisibleBody(Boolean visible, String reason, Boolean checked) {}

  record DataCardBody(String kind, String marketNo, String lang) {}

  record CompareBody(String marketNo, String viewpoint, String polymarketSlug, String lang) {}

  private final ContentOps ops;

  ContentAdminController(ContentOps ops) {
    this.ops = ops;
  }

  // ---------- 草稿箱 ----------

  @RequiresPermission(Permission.CONTENT_VIEW)
  @GetMapping("/api/admin/content/drafts")
  ContentAdminApi.Page<ContentAdminApi.Draft> drafts(@RequestParam(required = false) String status, @RequestParam(required = false) String kind,
      @RequestParam(required = false) String lang, @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize) {
    return ops.drafts(status, kind, lang, page, pageSize);
  }

  /** 一条草稿和同一次生成的另一种语言 */
  @RequiresPermission(Permission.CONTENT_VIEW)
  @GetMapping("/api/admin/content/drafts/{draftNo}")
  List<ContentAdminApi.Draft> group(@PathVariable String draftNo) {
    return ops.group(draftNo);
  }

  @RequiresPermission(Permission.CONTENT_EDIT)
  @PostMapping("/api/admin/content/drafts")
  List<ContentAdminApi.Draft> generate(@RequestBody ContentAdminApi.GenerateInput body) {
    return ops.generate(AdminContext.current(), body);
  }

  @RequiresPermission(Permission.CONTENT_EDIT)
  @PutMapping("/api/admin/content/drafts/{draftNo}")
  ContentAdminApi.Draft edit(@PathVariable String draftNo, @RequestBody ContentAdminApi.EditInput body) {
    return ops.edit(AdminContext.current(), draftNo, body);
  }

  @RequiresPermission(Permission.CONTENT_PUBLISH)
  @PostMapping("/api/admin/content/drafts/{draftNo}/publish")
  ContentAdminApi.Draft publish(@PathVariable String draftNo, @RequestBody HighRiskBody body) {
    return ops.publish(AdminContext.current(), draftNo, body == null ? null : body.reason(), body == null ? null : body.checked());
  }

  @RequiresPermission(Permission.CONTENT_EDIT)
  @PostMapping("/api/admin/content/drafts/{draftNo}/reject")
  ContentAdminApi.Draft reject(@PathVariable String draftNo, @RequestBody RejectBody body) {
    return ops.reject(AdminContext.current(), draftNo, body == null ? null : body.reason());
  }

  @RequiresPermission(Permission.CONTENT_EDIT)
  @PostMapping("/api/admin/content/drafts/{draftNo}/retry")
  ContentAdminApi.Draft retry(@PathVariable String draftNo) {
    return ops.retry(AdminContext.current(), draftNo);
  }

  /** 记一次复制（文字在页面上，复制由浏览器做；这里检查能不能复制并写日志、审计） */
  @RequiresPermission(Permission.CONTENT_VIEW)
  @PostMapping("/api/admin/content/drafts/{draftNo}/copied")
  ContentAdminApi.Draft copied(@PathVariable String draftNo) {
    return ops.copied(AdminContext.current(), draftNo);
  }

  // ---------- 用量和设置 ----------

  @RequiresPermission(Permission.CONTENT_VIEW)
  @GetMapping("/api/admin/content/status")
  ContentAdminApi.Status status() {
    return ops.status();
  }

  @RequiresPermission(Permission.CONTENT_PUBLISH)
  @PutMapping("/api/admin/content/settings")
  ContentAdminApi.Status settings(@RequestBody SettingsBody body) {
    return ops.settings(AdminContext.current(), body == null ? null : body.values(), body == null ? null : body.reason(), body == null ? null : body.checked());
  }

  /** 紧急停止 / 恢复 */
  @RequiresPermission(Permission.CONTENT_PUBLISH)
  @PostMapping("/api/admin/content/stop")
  ContentAdminApi.Status stop(@RequestBody StopBody body) {
    return ops.stop(AdminContext.current(), body == null ? null : body.stopped(), body == null ? null : body.reason(), body == null ? null : body.checked());
  }

  @RequiresPermission(Permission.CONTENT_VIEW)
  @GetMapping("/api/admin/content/words")
  List<ContentAdminApi.Word> words(@RequestParam(required = false) String kind) {
    return ops.words(kind);
  }

  @RequiresPermission(Permission.CONTENT_PUBLISH)
  @PostMapping("/api/admin/content/words")
  ContentAdminApi.Word addWord(@RequestBody WordBody body) {
    return ops.addWord(AdminContext.current(), body == null ? null : body.kind(), body == null ? null : body.word(), body == null ? null : body.reason(),
        body == null ? null : body.checked());
  }

  @RequiresPermission(Permission.CONTENT_PUBLISH)
  @PostMapping("/api/admin/content/words/{id}/delete")
  ContentAdminApi.Word deleteWord(@PathVariable String id, @RequestBody HighRiskBody body) {
    return ops.deleteWord(id, body == null ? null : body.reason(), body == null ? null : body.checked());
  }

  // ---------- 事件候选 ----------

  @RequiresPermission(Permission.CONTENT_VIEW)
  @GetMapping("/api/admin/content/candidates")
  ContentAdminApi.Candidates candidates() {
    return ops.candidates();
  }

  @RequiresPermission(Permission.CONTENT_VIEW)
  @GetMapping("/api/admin/content/econ")
  List<ContentAdminApi.Econ> econ() {
    return ops.econ();
  }

  @RequiresPermission(Permission.CONTENT_EDIT)
  @PostMapping("/api/admin/content/econ")
  ContentAdminApi.Econ addEcon(@RequestBody ContentAdminApi.EconInput body) {
    return ops.addEcon(AdminContext.current(), body);
  }

  @RequiresPermission(Permission.CONTENT_EDIT)
  @PutMapping("/api/admin/content/econ/{id}")
  ContentAdminApi.Econ updateEcon(@PathVariable String id, @RequestBody ContentAdminApi.EconInput body) {
    return ops.updateEcon(AdminContext.current(), id, body);
  }

  @RequiresPermission(Permission.CONTENT_EDIT)
  @PostMapping("/api/admin/content/econ/{id}/cancel")
  ContentAdminApi.Econ cancelEcon(@PathVariable String id) {
    return ops.cancelEcon(AdminContext.current(), id);
  }

  /** 立即按模板生成市场草稿（只是草稿，上架要 market:publish） */
  @RequiresPermission(Permission.CONTENT_EDIT)
  @PostMapping("/api/admin/content/econ/{id}/draft")
  ContentAdminApi.Econ draftEcon(@PathVariable String id) {
    return ops.draftEcon(AdminContext.current(), id);
  }

  // ---------- 模板 ----------

  @RequiresPermission(Permission.CONTENT_VIEW)
  @GetMapping("/api/admin/content/templates")
  List<ContentAdminApi.Template> templates() {
    return ops.templates();
  }

  @RequiresPermission(Permission.CONTENT_EDIT)
  @PutMapping("/api/admin/content/templates/{code}")
  ContentAdminApi.Template updateTemplate(@PathVariable String code, @RequestBody ContentAdminApi.TemplateInput body) {
    return ops.updateTemplate(AdminContext.current(), code, body);
  }

  // ---------- 页面 ----------

  @RequiresPermission(Permission.CONTENT_VIEW)
  @GetMapping("/api/admin/content/pages")
  ContentAdminApi.Pages pages() {
    return ops.pages();
  }

  @RequiresPermission(Permission.CONTENT_EDIT)
  @PostMapping("/api/admin/content/pages/btc-hourly/refresh")
  ContentAdminApi.PageRow refreshBtc() {
    return ops.refreshBtc(AdminContext.current());
  }

  @RequiresPermission(Permission.CONTENT_PUBLISH)
  @PostMapping("/api/admin/content/pages/{slug}/visibility")
  ContentAdminApi.PageRow visibility(@PathVariable String slug, @RequestBody VisibleBody body) {
    return ops.visibility(AdminContext.current(), slug, body == null ? null : body.visible(), body == null ? null : body.reason(),
        body == null ? null : body.checked());
  }

  // ---------- 工具 ----------

  @RequiresPermission(Permission.CONTENT_EDIT)
  @PostMapping("/api/admin/content/tools/data-card")
  ContentAdminApi.ToolResult dataCard(@RequestBody DataCardBody body) {
    return ops.dataCard(body == null ? null : body.kind(), body == null ? null : body.marketNo(), body == null ? null : body.lang());
  }

  @RequiresPermission(Permission.CONTENT_EDIT)
  @PostMapping("/api/admin/content/tools/compare")
  ContentAdminApi.ToolResult compare(@RequestBody CompareBody body) {
    return ops.compare(AdminContext.current(), body == null ? null : body.marketNo(), body == null ? null : body.viewpoint(),
        body == null ? null : body.polymarketSlug(), body == null ? null : body.lang());
  }

  // ---------- 日志 ----------

  @RequiresPermission(Permission.CONTENT_VIEW)
  @GetMapping("/api/admin/content/logs")
  ContentAdminApi.Page<ContentAdminApi.LogRow> logs(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
      @RequestParam(required = false) String action, @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "50") int pageSize) {
    return ops.logs(from, to, action, page, pageSize);
  }

  @RequiresPermission(Permission.CONTENT_VIEW)
  @GetMapping("/api/admin/content/logs/export")
  ResponseEntity<byte[]> exportLogs(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
      @RequestParam(required = false) String action) {
    String csv = ops.exportLogs(from, to, action);
    String name = ("content-logs-" + (from == null ? "all" : from) + "-" + (to == null ? "now" : to) + ".csv").replaceAll("[^A-Za-z0-9._-]", "_");
    return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
        .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8)).body(csv.getBytes(StandardCharsets.UTF_8));
  }
}
