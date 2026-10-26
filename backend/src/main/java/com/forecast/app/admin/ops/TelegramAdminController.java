package com.forecast.app.admin.ops;

import com.forecast.app.admin.security.AdminContext;
import com.forecast.app.admin.security.Permission;
import com.forecast.app.admin.security.RequiresPermission;
import com.forecast.app.growth.api.TelegramAdminApi;
import org.springframework.http.CacheControl;
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
 * 后台"Telegram 机器人"接口（forecast S16，MF-7、MQ-2；需求第 6.3 节；权限点 content:view 查看、content:publish ★ 改动）。字段见 {@link TelegramAdminApi}。
 */
@RestController
class TelegramAdminController {

  record TargetBody(String chatId, Boolean enabled, String reason) {}

  record ReasonBody(String reason) {}

  record ApproveBody(String fingerprint, Boolean checked, String reason) {}

  record StopBody(Boolean stop, String reason) {}

  private final TelegramOps ops;

  TelegramAdminController(TelegramOps ops) {
    this.ops = ops;
  }

  /** 机器人状态（是否配置 token、用户名、最近的错误、紧急停止）、频道、群、模板 */
  @RequiresPermission(Permission.CONTENT_VIEW)
  @GetMapping("/api/admin/telegram")
  TelegramAdminApi.Overview overview() {
    return ops.overview();
  }

  /** 发布日志：status = pending / sent / failed / skipped / stopped */
  @RequiresPermission(Permission.CONTENT_VIEW)
  @GetMapping("/api/admin/telegram/posts")
  TelegramAdminApi.Posts posts(@RequestParam(required = false) String status, @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int pageSize) {
    return ops.posts(status, page, pageSize);
  }

  /** 改频道（key = CHANNEL_ZH / CHANNEL_EN）：{"chatId": "@…", "enabled": true, "reason": "…"} */
  @RequiresPermission(Permission.CONTENT_PUBLISH)
  @PutMapping("/api/admin/telegram/targets/{key}")
  TelegramAdminApi.Target target(@PathVariable String key, @RequestBody(required = false) TargetBody b) {
    return ops.updateTarget(AdminContext.current(), key, b == null ? null : b.chatId(), b == null ? null : b.enabled(), b == null ? null : b.reason());
  }

  /** 关闭一个群的播报 */
  @RequiresPermission(Permission.CONTENT_PUBLISH)
  @PostMapping("/api/admin/telegram/groups/{chatId}/broadcast-off")
  TelegramAdminApi.Group groupOff(@PathVariable String chatId, @RequestBody(required = false) ReasonBody b) {
    return ops.stopGroup(AdminContext.current(), chatId, b == null ? null : b.reason());
  }

  /** 模板预览（用最近结束的一个市场生成）：文字和指纹；kind = RESULT，lang = zh-CN / en */
  @RequiresPermission(Permission.CONTENT_VIEW)
  @GetMapping("/api/admin/telegram/templates/{kind}/{lang}/preview")
  TelegramAdminApi.Preview preview(@PathVariable String kind, @PathVariable String lang) {
    return ops.preview(kind, lang);
  }

  /** 模板预览图 */
  @RequiresPermission(Permission.CONTENT_VIEW)
  @GetMapping("/api/admin/telegram/templates/{kind}/{lang}/preview.png")
  ResponseEntity<byte[]> previewPng(@PathVariable String kind, @PathVariable String lang) {
    return ops.previewPng(kind, lang).map(b -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.IMAGE_PNG).body(b))
        .orElse(ResponseEntity.notFound().build());
  }

  /** 审定：{"fingerprint": 预览给的, "checked": true（我确认按这个模板自动发布）, "reason": "…"} */
  @RequiresPermission(Permission.CONTENT_PUBLISH)
  @PostMapping("/api/admin/telegram/templates/{kind}/{lang}/approve")
  TelegramAdminApi.Template approve(@PathVariable String kind, @PathVariable String lang, @RequestBody(required = false) ApproveBody b) {
    return ops.approve(AdminContext.current(), kind, lang, b == null ? null : b.fingerprint(), b == null ? null : (b.checked() == null ? Boolean.FALSE : b.checked()),
        b == null ? null : b.reason());
  }

  /** 停用模板 */
  @RequiresPermission(Permission.CONTENT_PUBLISH)
  @PostMapping("/api/admin/telegram/templates/{kind}/{lang}/disable")
  TelegramAdminApi.Template disable(@PathVariable String kind, @PathVariable String lang, @RequestBody(required = false) ReasonBody b) {
    return ops.disable(AdminContext.current(), kind, lang, b == null ? null : b.reason());
  }

  /** 紧急停止 / 恢复：{"stop": true, "reason": "…"} */
  @RequiresPermission(Permission.CONTENT_PUBLISH)
  @PostMapping("/api/admin/telegram/stop")
  TelegramAdminApi.Status stop(@RequestBody(required = false) StopBody b) {
    return ops.stop(AdminContext.current(), b == null ? null : b.stop(), b == null ? null : b.reason());
  }
}
