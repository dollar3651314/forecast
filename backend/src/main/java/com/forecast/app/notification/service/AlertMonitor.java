package com.forecast.app.notification.service;

import com.forecast.app.common.health.AlertCheck;
import com.forecast.app.common.health.AlertProperties;
import com.forecast.app.notification.api.MailApi;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 监控告警邮件（需求 v2 E8，AC2-20）：每 15 秒把各模块的 {@link AlertCheck}（行情全部断流、扫块停滞、链节点连不上、邮件连续发送失败）查一遍，
 * 某一项变成异常时给告警邮箱（{@code FC_ALERT_EMAILS}）发"告警"邮件，恢复时发"已恢复"邮件。
 * <ul>
 *   <li><b>节流</b>：同一项告警在 {@code forecast.alert.throttle}（默认 30 分钟）内只发一封告警邮件。反复断开又恢复时，节流期内新开始的异常不发；
 *       如果异常一直持续到节流期满，再发一封。恢复邮件只在这次异常发过告警邮件时才发（没发告警就不发恢复，避免只收到"已恢复"）。</li>
 *   <li>邮件按邮箱发（{@link MailApi}，模板 {@code OPS_ALERT}、{@code OPS_RECOVERED}，中英文各一份；测试环境标题带"[测试]"），
 *       写成邮件任务，2 秒内由 {@link NotifyWorker} 发出，失败按任务表退避重试。收件人为空时只写日志。</li>
 *   <li>每次变化都写日志（异常 ERROR、恢复 WARN），没有邮箱也能在日志里查到。</li>
 *   <li>健康检查里的 {@code alerts} 不经过这里（直接调用各项检查），这里只管发邮件；{@code forecast.alert.enabled=false}（后端测试）时不定时运行。</li>
 * </ul>
 * 状态只在内存里：应用重启后，还在异常的项会重新发一封告警邮件。某一项不再出现（不再检查，例如热钱包余额告警的下限改回 0）时只清掉状态，不发恢复邮件。
 */
@Component
class AlertMonitor {

  private static final Logger log = LoggerFactory.getLogger(AlertMonitor.class);
  static final String ALERT_TEMPLATE = "OPS_ALERT";
  static final String RECOVERED_TEMPLATE = "OPS_RECOVERED";

  /** 一项告警的状态 */
  private static final class State {
    boolean down;
    Instant since;
    /** 这一项最近一次发告警邮件的时间（跨多次异常保留，用来节流） */
    Instant lastMailAt;
    /** 这次异常发过告警邮件 */
    boolean mailed;
  }

  private final List<AlertCheck> checks;
  private final AlertProperties props;
  private final MailApi mail;
  private final Clock clock;
  private final Map<String, State> states = new HashMap<>();

  AlertMonitor(List<AlertCheck> checks, AlertProperties props, MailApi mail, Clock clock) {
    this.checks = checks;
    this.props = props;
    this.mail = mail;
    this.clock = clock;
  }

  @Scheduled(fixedDelay = 15_000, initialDelay = 30_000)
  void tick() {
    if (!props.enabled()) return;
    try {
      check();
    } catch (RuntimeException e) {
      log.error("监控告警检查失败", e);
    }
  }

  /** 查一遍，按变化发邮件。测试里直接调用 */
  synchronized void check() {
    Instant now = clock.instant();
    Set<String> seen = new HashSet<>();
    boolean allEvaluated = true;
    for (AlertCheck c : checks) {
      List<AlertCheck.Item> items;
      try {
        items = c.evaluate();
      } catch (RuntimeException e) {
        log.error("告警检查出错 {}", c.getClass().getSimpleName(), e);
        allEvaluated = false;
        continue;
      }
      for (AlertCheck.Item i : items) {
        seen.add(i.name());
        update(i, now);
      }
    }
    // 不再检查的项（例如热钱包余额告警的下限改回 0）：不发恢复邮件，只清掉状态，以后重新启用时按新的一次异常处理。
    // 有检查出错时这一轮不清（出错的检查的项本轮没有出现，不代表不再检查）
    if (allEvaluated) {
      for (Map.Entry<String, State> e : states.entrySet()) {
        State s = e.getValue();
        if (s.down && !seen.contains(e.getKey())) {
          log.warn("监控告警项不再检查（配置改了），清除异常状态：{}，异常开始于 {}", e.getKey(), time(s.since));
          s.down = false;
          s.mailed = false;
        }
      }
    }
  }

  private void update(AlertCheck.Item i, Instant now) {
    State s = states.computeIfAbsent(i.name(), k -> new State());
    if (!i.ok()) {
      if (!s.down) {
        s.down = true;
        s.since = now;
        s.mailed = false;
        log.error("监控告警：{}（{}）：{}", i.titleZh(), i.name(), i.detail());
      }
      boolean throttled = s.lastMailAt != null && Duration.between(s.lastMailAt, now).compareTo(props.throttle()) < 0;
      if (!s.mailed && !throttled) {
        send(ALERT_TEMPLATE, Map.of("itemZh", i.titleZh(), "itemEn", i.titleEn(), "detail", i.detail(), "since", time(s.since)));
        s.mailed = true;
        s.lastMailAt = now;
      }
    } else if (s.down) {
      log.warn("监控告警已恢复：{}（{}），异常开始于 {}", i.titleZh(), i.name(), time(s.since));
      if (s.mailed) send(RECOVERED_TEMPLATE, Map.of("itemZh", i.titleZh(), "itemEn", i.titleEn(), "since", time(s.since), "at", time(now)));
      s.down = false;
      s.mailed = false;
    }
  }

  private void send(String template, Map<String, String> params) {
    List<String> to = props.recipients();
    if (to.isEmpty()) {
      log.warn("没有配置告警邮箱（FC_ALERT_EMAILS），只记了日志：{} {}", template, params);
      return;
    }
    for (String email : to) {
      try {
        mail.send(email, template, params);
      } catch (RuntimeException e) {
        log.error("告警邮件写入发送任务失败 {}", template, e);
      }
    }
  }

  private static String time(Instant t) {
    return t.truncatedTo(ChronoUnit.SECONDS).toString();
  }
}
