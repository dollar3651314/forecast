package com.forecast.app.admin.ops;

import com.forecast.app.admin.security.AdminContext;
import com.forecast.app.admin.security.Permission;
import com.forecast.app.admin.security.RequiresPermission;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后台"资金归集"接口（v2 第一阶段：以太坊、BSC；技术方案/v2-半自动归集.md 第 4.9 节）。TRON 的接口（待签名清单、导入签名、广播、作废、冷钱包地址）是下一阶段。
 * 路径里的批次号是对外编号（雪花 ID，字符串）。
 */
@RestController
class SweepController {

  record ChainBody(String chain) {}

  record RunBody(String chain, String reason) {}

  record ReasonBody(String reason) {}

  private final SweepOps ops;

  SweepController(SweepOps ops) {
    this.ops = ops;
  }

  @RequiresPermission(Permission.SWEEP_VIEW)
  @GetMapping("/api/admin/sweep/overview")
  List<SweepOps.OverviewView> overview() {
    return ops.overview();
  }

  /** 实时预览：查链上余额（只读） */
  @RequiresPermission(Permission.SWEEP_VIEW)
  @PostMapping("/api/admin/sweep/preview")
  SweepOps.PreviewView preview(@RequestBody ChainBody body) {
    return ops.preview(body == null ? null : body.chain());
  }

  /** 执行归集（高危，原因必填）：返回批次号 */
  @RequiresPermission(Permission.SWEEP_OPERATE)
  @PostMapping("/api/admin/sweep/batches")
  Map<String, String> run(@RequestBody RunBody body) {
    return Map.of("no", ops.run(AdminContext.current(), body == null ? null : body.chain(), body == null ? null : body.reason()));
  }

  @RequiresPermission(Permission.SWEEP_VIEW)
  @GetMapping("/api/admin/sweep/batches")
  Ops.Page<SweepOps.BatchView> batches(@RequestParam(required = false) String chain, @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int pageSize) {
    return ops.batches(chain, page, pageSize);
  }

  @RequiresPermission(Permission.SWEEP_VIEW)
  @GetMapping("/api/admin/sweep/batches/{no}")
  SweepOps.BatchDetailView batch(@PathVariable long no) {
    return ops.batch(no);
  }

  /** 失败或部分成功的批次：没成功的地址重新执行（新批次，高危，原因必填） */
  @RequiresPermission(Permission.SWEEP_OPERATE)
  @PostMapping("/api/admin/sweep/batches/{no}/retry")
  Map<String, String> retry(@PathVariable long no, @RequestBody ReasonBody body) {
    return Map.of("no", ops.retry(AdminContext.current(), no, body == null ? null : body.reason()));
  }

  @RequiresPermission(Permission.SWEEP_VIEW)
  @GetMapping("/api/admin/sweep/settings")
  SweepOps.SettingsView settings() {
    return ops.settings();
  }

  /** 改设置（高危，原因必填，带版本号） */
  @RequiresPermission(Permission.SWEEP_CONFIG)
  @PutMapping("/api/admin/sweep/settings")
  SweepOps.SettingsView save(@RequestBody SweepOps.SaveBody body) {
    return ops.save(AdminContext.current(), body);
  }
}
