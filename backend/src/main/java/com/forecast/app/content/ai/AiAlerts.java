package com.forecast.app.content.ai;

import com.forecast.app.content.core.ContentLog;
import com.forecast.app.content.core.ContentMapper;
import com.forecast.app.content.core.ContentRows;
import com.forecast.app.content.core.Settings;
import com.forecast.app.notification.api.StaffAlertApi;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Gemini 的告警邮件（forecast S15，自动化需求第 8 节：未配置、key 不可用、额度用完、连续失败——<b>每种每天最多一封</b>）。
 * 发给有 content:publish 的在职管理员（模板 CONTENT_ALERT）。"今天发过没有"记在 {@code content_ai_day.alerts}（条件更新，多实例也只发一封）。
 * 发邮件失败只记日志，不影响调用方。
 */
@Component
public class AiAlerts {

  private static final Logger log = LoggerFactory.getLogger(AiAlerts.class);

  private final ContentMapper mapper;
  private final StaffAlertApi staff;
  private final Settings settings;
  private final ContentLog contentLog;
  private final Clock clock;
  private final TransactionTemplate tx;

  AiAlerts(ContentMapper mapper, StaffAlertApi staff, Settings settings, ContentLog contentLog, Clock clock, PlatformTransactionManager tm) {
    this.mapper = mapper;
    this.staff = staff;
    this.settings = settings;
    this.contentLog = contentLog;
    this.clock = clock;
    this.tx = new TransactionTemplate(tm);
  }

  /** 这种告警今天还没发过就发一封 */
  public void raise(String kind, String what, String whatEn, String detail, String detailEn) {
    try {
      LocalDate day = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
      mapper.ensureAiDay(day);
      if (mapper.markAlert(day, kind) != 1) return;
      ContentRows.AiDay d = mapper.aiDay(day);
      Map<String, String> p = new LinkedHashMap<>();
      p.put("what", what);
      p.put("whatEn", whatEn);
      p.put("detail", detail);
      p.put("detailEn", detailEn);
      p.put("used", String.valueOf(d == null ? 0 : d.used));
      p.put("limit", String.valueOf(settings.integer(Settings.AI_DAILY_LIMIT, 300)));
      tx.executeWithoutResult(s -> staff.notifyStaff("CONTENT_ALERT", p));
      contentLog.write("ALERT", null, kind, null, "OK", what + "：" + detail, 0, null);
    } catch (RuntimeException e) {
      log.warn("内容中心告警 {} 没发出去", kind, e);
    }
  }
}
