package com.forecast.app.admin.ops;

import com.forecast.app.admin.security.AdminContext;
import com.forecast.app.admin.security.Permission;
import com.forecast.app.admin.security.RequiresPermission;
import com.forecast.app.growth.api.WaitlistAdminApi;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后台"等候页订阅"接口（forecast S16，MF-8；需求第 6.2 节；权限点 growth:view 查看、content:publish ★ 发送上线通知）。字段见 {@link WaitlistAdminApi}。
 */
@RestController
class WaitlistAdminController {

  record LaunchBody(Boolean checked, String reason) {}

  private final WaitlistOps ops;

  WaitlistAdminController(WaitlistOps ops) {
    this.ops = ops;
  }

  /** 订阅列表（邮箱打码）、各状态的数量、上线通知的状态；status = pending / confirmed / notified / unsubscribed */
  @RequiresPermission(Permission.GROWTH_VIEW)
  @GetMapping("/api/admin/waitlist")
  WaitlistAdminApi.Page list(@RequestParam(required = false) String status, @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int pageSize) {
    return ops.list(status, page, pageSize);
  }

  /** 发送上线通知（只能一次）：{"checked": true, "reason": "…"} */
  @RequiresPermission(Permission.CONTENT_PUBLISH)
  @PostMapping("/api/admin/waitlist/launch")
  WaitlistAdminApi.Launch launch(@RequestBody(required = false) LaunchBody b) {
    return ops.launch(AdminContext.current(), b == null ? null : b.checked(), b == null ? null : b.reason());
  }
}
