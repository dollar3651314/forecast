package com.forecast.app.admin.ops;

import com.forecast.app.admin.security.AdminContext;
import com.forecast.app.admin.security.Audit;
import com.forecast.app.common.error.ValidationException;
import com.forecast.app.growth.api.WaitlistAdminApi;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 后台"等候页订阅"（forecast S16，MF-8、SQ-22）：数据在 growth 模块（{@link WaitlistAdminApi}），这里负责"发送上线通知"的高危确认和审计
 * （原因必填、勾选"我确认只发这一次"；只能发一次，第二次 409 LAUNCH_ALREADY_SENT，失败也写审计）。
 */
@Service
class WaitlistOps {

  private final WaitlistAdminApi waitlist;
  private final Audit audit;

  WaitlistOps(WaitlistAdminApi waitlist, Audit audit) {
    this.waitlist = waitlist;
    this.audit = audit;
  }

  WaitlistAdminApi.Page list(String status, int page, int pageSize) {
    return waitlist.list(status, page, pageSize);
  }

  WaitlistAdminApi.Launch launch(AdminContext actor, Boolean checked, String reason) {
    List<ValidationException.Field> errs = new ArrayList<>();
    try {
      Audit.requireReason(reason);
    } catch (ValidationException e) {
      errs.addAll(e.fields());
    }
    if (!Boolean.TRUE.equals(checked)) errs.add(new ValidationException.Field("checked", "required"));
    if (!errs.isEmpty()) throw new ValidationException(errs);
    String r = reason.strip();
    return audit.run("waitlist.launch", "launch", true, r, () -> {
      WaitlistAdminApi.Launch l = waitlist.startLaunch(actor.adminId(), r);
      Map<String, Object> after = new LinkedHashMap<>();
      after.put("status", l.status());
      after.put("recipients", l.recipients());
      return Audit.Change.of(l, Map.of("status", "none"), after);
    });
  }

  /** Cloudflare 导出文件的格式（cf/src/admin.ts 的 EXPORT_FORMAT） */
  static final String CF_FORMAT = "fc-cf-waitlist-v1";
  /** 一次最多导入几项（Cloudflare 每份导出最多 2000 项，这里留余量） */
  static final int IMPORT_MAX = 5000;

  /**
   * forecast S19（P15）：从 Cloudflare 导入等候页名单（content:publish ★，原因必填、勾选"我确认导入"）。审计只记数量，不记邮箱。
   * 文件格式不对、项数为 0 或太多：400，字段 file；每一项的问题在结果里逐行列出（合法的照常导入，C108）。
   */
  WaitlistAdminApi.ImportResult importFromCloudflare(AdminContext actor, Boolean checked, String reason, String format, List<WaitlistAdminApi.ImportItem> items) {
    List<ValidationException.Field> errs = new ArrayList<>();
    try {
      Audit.requireReason(reason);
    } catch (ValidationException e) {
      errs.addAll(e.fields());
    }
    if (!Boolean.TRUE.equals(checked)) errs.add(new ValidationException.Field("checked", "required"));
    if (!CF_FORMAT.equals(format)) errs.add(new ValidationException.Field("file", "formatInvalid"));
    else if (items == null || items.isEmpty()) errs.add(new ValidationException.Field("file", "empty"));
    else if (items.size() > IMPORT_MAX) errs.add(new ValidationException.Field("file", "tooMany"));
    if (!errs.isEmpty()) throw new ValidationException(errs);
    String r = reason.strip();
    return audit.run("waitlist.import", "cloudflare", true, r, () -> {
      WaitlistAdminApi.ImportResult res = waitlist.importFromCloudflare(items);
      Map<String, Object> after = new LinkedHashMap<>();
      after.put("total", res.total());
      after.put("added", res.added());
      after.put("confirmed", res.confirmed());
      after.put("unsubscribed", res.unsubscribed());
      after.put("unchanged", res.unchanged());
      after.put("notAdded", res.notAdded());
      after.put("invalid", res.invalid());
      after.put("afterLaunch", res.afterLaunch());
      return Audit.Change.of(res, Map.of(), after);
    });
  }
}
