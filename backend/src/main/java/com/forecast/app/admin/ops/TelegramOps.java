package com.forecast.app.admin.ops;

import com.forecast.app.admin.security.AdminContext;
import com.forecast.app.admin.security.Audit;
import com.forecast.app.common.error.BizException;
import com.forecast.app.common.error.ValidationException;
import com.forecast.app.growth.api.TelegramAdminApi;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 后台"Telegram 机器人"（forecast S16，MF-7、MQ-2）：数据在 growth 模块（{@link TelegramAdminApi}），这里负责高危确认和审计。
 * 改频道和开关、审定 / 停用模板、紧急停止 / 恢复、关闭群播报都是 content:publish ★：原因必填；审定还要勾选"我确认按这个模板自动发布"（需求第 6.3 节）。
 */
@Service
class TelegramOps {

  private final TelegramAdminApi tg;
  private final Audit audit;

  TelegramOps(TelegramAdminApi tg, Audit audit) {
    this.tg = tg;
    this.audit = audit;
  }

  TelegramAdminApi.Overview overview() {
    return tg.overview();
  }

  TelegramAdminApi.Posts posts(String status, int page, int pageSize) {
    return tg.posts(status, page, pageSize);
  }

  TelegramAdminApi.Preview preview(String kind, String lang) {
    return tg.preview(kind, lang).orElseThrow(() -> new BizException("TELEGRAM_NO_PREVIEW", "还没有结束的市场，没法预览"));
  }

  Optional<byte[]> previewPng(String kind, String lang) {
    return tg.previewPng(kind, lang);
  }

  TelegramAdminApi.Target updateTarget(AdminContext actor, String key, String chatId, Boolean enabled, String reason) {
    String r = reason(reason, null);
    TelegramAdminApi.Target before = tg.overview().targets().stream().filter(t -> t.key().equals(key)).findFirst().orElse(null);
    return audit.run("telegram.target", key, true, r, () -> {
      TelegramAdminApi.Target t = tg.updateTarget(key, chatId, Boolean.TRUE.equals(enabled), actor.adminId());
      return Audit.Change.of(t, before == null ? null : targetMap(before), targetMap(t));
    });
  }

  TelegramAdminApi.Group stopGroup(AdminContext actor, String chatId, String reason) {
    String r = reason(reason, null);
    return audit.run("telegram.group.off", chatId, true, r, () -> {
      TelegramAdminApi.Group g = tg.stopGroupBroadcast(chatId, actor.adminId());
      return Audit.Change.of(g, Map.of("broadcast", true), Map.of("broadcast", g.broadcast()));
    });
  }

  TelegramAdminApi.Template approve(AdminContext actor, String kind, String lang, String fingerprint, Boolean checked, String reason) {
    String r = reason(reason, Boolean.TRUE.equals(checked));
    return audit.run("telegram.template.approve", kind + ":" + lang, true, r, () -> {
      TelegramAdminApi.Template t = tg.approve(kind, lang, fingerprint, actor.adminId());
      Map<String, Object> after = new LinkedHashMap<>();
      after.put("status", t.status());
      after.put("fingerprint", fingerprint);
      return Audit.Change.of(t, null, after);
    });
  }

  TelegramAdminApi.Template disable(AdminContext actor, String kind, String lang, String reason) {
    String r = reason(reason, null);
    return audit.run("telegram.template.disable", kind + ":" + lang, true, r, () -> {
      TelegramAdminApi.Template t = tg.disable(kind, lang, actor.adminId());
      return Audit.Change.of(t, null, Map.of("status", t.status()));
    });
  }

  TelegramAdminApi.Status stop(AdminContext actor, Boolean stop, String reason) {
    String r = reason(reason, null);
    boolean s = Boolean.TRUE.equals(stop);
    return audit.run(s ? "telegram.stop" : "telegram.resume", "telegram", true, r, () -> {
      TelegramAdminApi.Status st = tg.setStop(s, r, actor.adminId());
      return Audit.Change.of(st, Map.of("emergencyStop", !s), Map.of("emergencyStop", st.emergencyStop()));
    });
  }

  /** 原因必填；checked 不为 null 的操作还要勾选 */
  private static String reason(String reason, Boolean checkedRequiredValue) {
    List<ValidationException.Field> errs = new ArrayList<>();
    try {
      Audit.requireReason(reason);
    } catch (ValidationException e) {
      errs.addAll(e.fields());
    }
    if (checkedRequiredValue != null && !checkedRequiredValue) errs.add(new ValidationException.Field("checked", "required"));
    if (!errs.isEmpty()) throw new ValidationException(errs);
    return reason.strip();
  }

  private static Map<String, Object> targetMap(TelegramAdminApi.Target t) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("chatId", t.chatId());
    m.put("enabled", t.enabled());
    return m;
  }
}
