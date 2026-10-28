package com.forecast.app.admin.ops;

import com.forecast.app.admin.security.AdminContext;
import com.forecast.app.admin.security.Permission;
import com.forecast.app.admin.security.RequiresPermission;
import java.nio.charset.StandardCharsets;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 人工调账（forecast S17B，需求说明书 v1.5 第 21 节）的后台接口。生产可用（和只在测试环境的测试入金不同）。 */
@RestController
class AdjustAdminController {

  private final AdjustOps ops;

  AdjustAdminController(AdjustOps ops) {
    this.ops = ops;
  }

  /**
   * 人工加款 / 扣款（高危）：{"direction":"credit|debit","category":"deposit_missing|wrong_credit|other","amount":"…","refNo":"…","reason":"…",
   * "requestId":"…","checked":true,"overLimitConfirmed":false}
   */
  @RequiresPermission(Permission.BALANCE_ADJUST)
  @PostMapping("/api/admin/customers/{id}/adjust")
  AdjustOps.AdjustResult adjust(@PathVariable long id, @RequestBody(required = false) AdjustOps.AdjustBody body) {
    return ops.adjust(AdminContext.current(), id, body);
  }

  /** 人工调账记录（资金 → 人工调账记录），新的在前 */
  @RequiresPermission(Permission.RECON_VIEW)
  @GetMapping("/api/admin/balance-adjustments")
  Ops.Page<AdjustOps.AdjustView> list(@RequestParam(required = false) String customerId, @RequestParam(required = false) String direction,
      @RequestParam(required = false) String category, @RequestParam(required = false) String from, @RequestParam(required = false) String to,
      @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize) {
    return ops.list(customerId, direction, category, from, to, page, pageSize);
  }

  /** 导出 CSV（同样的筛选；最多 1 万行；写审计 adjust.export）；lang = zh / en（表头和文字） */
  @RequiresPermission(Permission.RECON_VIEW)
  @GetMapping("/api/admin/balance-adjustments/export")
  ResponseEntity<byte[]> export(@RequestParam(required = false) String customerId, @RequestParam(required = false) String direction,
      @RequestParam(required = false) String category, @RequestParam(required = false) String from, @RequestParam(required = false) String to,
      @RequestParam(required = false) String lang) {
    String csv = ops.export(customerId, direction, category, from, to, lang);
    String name = "balance-adjustments-" + (from == null ? "all" : from) + "-" + (to == null ? "now" : to) + ".csv";
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name.replaceAll("[^A-Za-z0-9._-]", "_")).build().toString())
        .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8)).body(csv.getBytes(StandardCharsets.UTF_8));
  }
}
