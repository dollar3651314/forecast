package com.forecast.app.admin.ops;

import com.forecast.app.admin.security.Permission;
import com.forecast.app.admin.security.RequiresPermission;
import com.forecast.app.growth.api.GrowthAdminApi;
import java.nio.charset.StandardCharsets;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后台"来源统计"接口（forecast S13，需求第 6.2 节"来源统计"、MF-1、MF-10、AC-94；权限点 growth:view）。字段见 {@link GrowthAdminApi}。
 */
@RestController
class GrowthAdminController {

  private final GrowthOps ops;

  GrowthAdminController(GrowthOps ops) {
    this.ops = ops;
  }

  /** 按注册周和来源的访问、注册、首充、激活、第 2 周仍下注；按周的有效市场率和每场下注人数；已结束的市场。from、to：YYYY-MM-DD（UTC），basis：first / reg */
  @RequiresPermission(Permission.GROWTH_VIEW)
  @GetMapping("/api/admin/growth/report")
  GrowthAdminApi.Report report(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
      @RequestParam(required = false) String basis) {
    return ops.report(from, to, basis);
  }

  /** CSV 导出：kind = sources / markets / customers；lang = zh / en（表头）。写审计 */
  @RequiresPermission(Permission.GROWTH_VIEW)
  @GetMapping("/api/admin/growth/export")
  ResponseEntity<byte[]> export(@RequestParam(required = false) String kind, @RequestParam(required = false) String from,
      @RequestParam(required = false) String to, @RequestParam(required = false) String basis, @RequestParam(required = false) String lang) {
    String csv = ops.export(kind, from, to, basis, lang);
    String name = "growth-" + kind + "-" + (from == null ? "recent" : from) + "-" + (to == null ? "now" : to) + ".csv";
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name.replaceAll("[^A-Za-z0-9._-]", "_")).build().toString())
        .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8)).body(csv.getBytes(StandardCharsets.UTF_8));
  }
}
