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
}
