package com.forecast.app.admin.ops;

import com.forecast.app.admin.security.AdminContext;
import com.forecast.app.admin.security.AdminNames;
import com.forecast.app.admin.security.Audit;
import com.forecast.app.common.config.ConfigKey;
import com.forecast.app.common.config.SystemConfig;
import com.forecast.app.common.error.BizException;
import com.forecast.app.common.error.ValidationException;
import com.forecast.app.common.health.AlertProperties;
import com.forecast.app.notification.api.MailApi;
import com.forecast.app.wallet.api.AutoApprove;
import com.forecast.app.wallet.api.AutoApproveAdminApi;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 后台"出金审核 → 自动审批规则"（v2，技术方案/v2-提现自动审批.md 第 7 节）：读取 / 保存规则配置、修改记录，出金列表和详情里的判定摘要、完整判定记录。
 * <ul>
 *   <li>配置存在系统配置（{@code withdraw.autoApprove.*}），一次保存 = 一个事务里改所有变化的键 + 版本号 +1；{@code expectedVersion} 不对返回 409 {@code CONFIG_CHANGED}。</li>
 *   <li>保存是高危操作：权限 {@code withdrawal:auto_rule_edit}、原因必填、审计 {@code withdrawal.auto_rule_update}（修改前 / 修改后只有变化的项，对象是新版本号）。</li>
 *   <li>总开关被打开或关闭时给告警邮箱（{@code FC_ALERT_EMAILS}，运维和风控）发一封邮件，防止有人悄悄打开。</li>
 * </ul>
 */
@Service
class AutoApproveOps {

  private static final Logger log = LoggerFactory.getLogger(AutoApproveOps.class);
  private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() {};
  static final String AUDIT_ACTION = "withdrawal.auto_rule_update";
  static final String MAIL_TEMPLATE = "OPS_AUTO_APPROVE_SWITCH";

  /** 字段和原型 AutoRuleSetting 一致 */
  record RuleView(String code, boolean enabled, Map<String, String> params) {}

  /** 字段和原型 AutoApproveSettings 一致 */
  record SettingsView(boolean masterEnabled, List<RuleView> rules, long version, String updatedAt, String updatedBy) {}

  /** 保存：一次提交总开关和 13 条规则；reason 必填，expectedVersion 是页面打开时读到的版本 */
  record SaveBody(Boolean masterEnabled, List<RuleView> rules, String reason, Long expectedVersion) {}

  record FieldChange(String field, String before, String after) {}

  /** 字段和原型 AutoApproveChange 一致 */
  record ChangeView(String id, long version, String time, String adminName, String reason, List<FieldChange> changes) {}

  /** 字段和原型 AutoReviewSummary 一致：phase precheck / final，outcome auto_approved / manual / master_off / error */
  record ReviewView(String phase, String outcome, List<String> failed, String decidedAt) {}

  record RuleResultView(String code, String status, Map<String, String> values) {}

  /** 字段和原型 AutoReviewDetail 一致 */
  record ReviewDetailView(String phase, String outcome, List<String> failed, String decidedAt, boolean masterEnabled, List<RuleResultView> rules,
      long configVersion, String configChangedAt, String configChangedBy, ReviewView precheck) {}

  private final AutoApproveAdminApi auto;
  private final SystemConfig config;
  private final AutoRuleAuditMapper audits;
  private final AdminNames names;
  private final Audit audit;
  private final MailApi mail;
  private final AlertProperties alerts;
  private final ObjectMapper json;
  private final Clock clock;

  AutoApproveOps(AutoApproveAdminApi auto, SystemConfig config, AutoRuleAuditMapper audits, AdminNames names, Audit audit, MailApi mail,
      AlertProperties alerts, ObjectMapper json, Clock clock) {
    this.auto = auto;
    this.config = config;
    this.audits = audits;
    this.names = names;
    this.audit = audit;
    this.mail = mail;
    this.alerts = alerts;
    this.json = json;
    this.clock = clock;
  }

  // ---------- 规则配置 ----------

  @Transactional(readOnly = true)
  SettingsView settings() {
    config.refresh();
    AutoApprove.Settings s = auto.settings();
    SystemConfig.Entry v = config.list().stream().filter(e -> e.key() == ConfigKey.AUTO_APPROVE_VERSION).findFirst().orElse(null);
    String by = v == null || v.updatedBy() == null ? null : names.of(List.of(v.updatedBy())).get(v.updatedBy());
    String at = v == null || v.updatedBy() == null ? null : Ops.time(v.updatedAt());
    return new SettingsView(s.masterEnabled(), s.rules().stream().map(r -> new RuleView(r.code(), r.enabled(), r.params())).toList(), s.version(), at, by);
  }

  /** 修改记录里的字段名：master，或"规则.参数"（R1.enabled、R1.maxAmount） */
  private static String field(String configKey) {
    if (configKey.equals(AutoApprove.MASTER_KEY)) return "master";
    return configKey.substring(AutoApprove.PREFIX.length());
  }

  SettingsView save(AdminContext actor, SaveBody body) {
    SaveBody b = body == null ? new SaveBody(null, null, null, null) : body;
    String reason = Audit.requireReason(b.reason());
    if (b.expectedVersion() == null || b.masterEnabled() == null) {
      throw new ValidationException(List.of(new ValidationException.Field(b.expectedVersion() == null ? "expectedVersion" : "masterEnabled", "required")));
    }
    long expected = b.expectedVersion();
    return audit.run(AUDIT_ACTION, "v" + (expected + 1), true, reason, () -> {
      List<AutoApprove.Problem> problems = new ArrayList<>();
      List<AutoApprove.RuleSetting> input = b.rules() == null ? null
          : b.rules().stream().filter(Objects::nonNull).map(r -> new AutoApprove.RuleSetting(r.code(), r.enabled(), r.params())).toList();
      AutoApprove.Settings next = AutoApprove.normalize(b.masterEnabled(), input, problems);
      if (next == null) throw new ValidationException(problems.stream().map(p -> new ValidationException.Field(p.field(), p.code())).toList());
      Map<ConfigKey, String> values = new LinkedHashMap<>();
      next.toConfig().forEach((k, v) -> values.put(ConfigKey.of(k).orElseThrow(), v));
      LocalDateTime now = LocalDateTime.now(clock.withZone(ZoneOffset.UTC));
      Map<ConfigKey, String[]> changed = config.setVersioned(ConfigKey.AUTO_APPROVE_VERSION, expected, values, actor.adminId(), now);
      if (changed.isEmpty()) throw new BizException("NO_CHANGE", "没有修改");
      Map<String, Object> before = new LinkedHashMap<>();
      Map<String, Object> after = new LinkedHashMap<>();
      changed.forEach((k, v) -> {
        before.put(field(k.key), v[0] == null ? "" : v[0]);
        after.put(field(k.key), v[1]);
      });
      String[] master = changed.get(ConfigKey.AUTO_APPROVE_ENABLED);
      if (master != null) switchMail(actor, Boolean.parseBoolean(master[1]), expected + 1, reason, now);
      return Audit.Change.of(settings(), before, after);
    });
  }

  /** 总开关打开 / 关闭：告警邮件（收件人为空时只记日志）；写邮件任务失败不影响保存 */
  private void switchMail(AdminContext actor, boolean on, long version, String reason, LocalDateTime at) {
    String who = names.of(List.of(actor.adminId())).getOrDefault(actor.adminId(), actor.username());
    log.warn("提现自动审批总开关已{}（第 {} 版），操作人 {}，原因：{}", on ? "打开" : "关闭", version, who, reason);
    Map<String, String> p = Map.of("stateZh", on ? "打开" : "关闭", "stateEn", on ? "ON" : "OFF", "version", String.valueOf(version), "admin", who,
        "reason", reason, "at", at.truncatedTo(ChronoUnit.SECONDS).toInstant(ZoneOffset.UTC).toString());
    List<String> to = alerts.recipients();
    if (to.isEmpty()) log.warn("没有配置告警邮箱（FC_ALERT_EMAILS），总开关变更只记了日志");
    for (String email : to) {
      try {
        mail.send(email, MAIL_TEMPLATE, p);
      } catch (RuntimeException e) {
        log.error("总开关变更邮件写入发送任务失败", e);
      }
    }
  }

  /** 字段的显示顺序：总开关，然后按规则编号、每条规则先开关后数值 */
  private static int order(String field) {
    if ("master".equals(field)) return -1;
    List<String> keys = new ArrayList<>(AutoApprove.parse(Map.of()).toConfig().keySet());
    int i = keys.indexOf(AutoApprove.PREFIX + field);
    return i < 0 ? Integer.MAX_VALUE : i;
  }

  @Transactional(readOnly = true)
  List<ChangeView> changes() {
    List<AutoRuleAuditMapper.Row> rows = audits.changes(20);
    Map<Long, String> who = names.of(rows.stream().map(r -> r.adminId).filter(Objects::nonNull).toList());
    return rows.stream().map(r -> {
      Map<String, Object> before = parse(r.beforeJson);
      Map<String, Object> after = parse(r.afterJson);
      List<FieldChange> cs = after.keySet().stream().sorted((x, y) -> Integer.compare(order(x), order(y)))
          .map(k -> new FieldChange(k, str(before.get(k)), str(after.get(k)))).toList();
      return new ChangeView(String.valueOf(r.id), version(r.target), Ops.time(r.createdAt), r.adminId == null ? r.adminName : who.getOrDefault(r.adminId, r.adminName),
          r.reason, cs);
    }).toList();
  }

  private Map<String, Object> parse(String s) {
    return s == null || s.isBlank() ? new LinkedHashMap<>() : json.readValue(s, MAP);
  }

  private static String str(Object o) {
    return o == null ? "" : String.valueOf(o);
  }

  private static long version(String target) {
    try {
      return target != null && target.startsWith("v") ? Long.parseLong(target.substring(1)) : 0;
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  // ---------- 出金列表和详情里的判定 ----------

  /** 后台筛选值 → 钱包模块的筛选值；不合法时字段错误 */
  static String filter(String api) {
    if (api == null || api.isBlank()) return null;
    String v = api.strip();
    return switch (v) {
      case "auto_approved", "manual", "master_off", "none" -> v.toUpperCase();
      default -> {
        if (AutoApprove.Rule.of(v).isPresent()) yield v;
        throw new ValidationException(List.of(new ValidationException.Field("autoReview", "invalid")));
      }
    };
  }

  static ReviewView view(AutoApproveAdminApi.Summary s) {
    return s == null ? null : new ReviewView(s.phase().toLowerCase(), s.outcome().toLowerCase(), s.failed(), Ops.time(s.decidedAt()));
  }

  /** 列表：内部 id → 摘要 */
  Map<Long, ReviewView> summaries(Collection<Long> withdrawalIds) {
    Map<Long, ReviewView> out = new HashMap<>();
    auto.latest(withdrawalIds).forEach((k, v) -> out.put(k, view(v)));
    return out;
  }

  /** 详情：完整的判定记录（逐条结果、判定时的配置版本和这一版的修改人、提交时的预判摘要）；没有判定记录时为空 */
  ReviewDetailView detail(long withdrawalId) {
    return auto.detail(withdrawalId).map(d -> {
      String changedAt = null;
      String changedBy = null;
      if (d.configVersion() > 0) {
        List<AutoRuleAuditMapper.Row> rows = audits.versions(List.of("v" + d.configVersion()));
        if (!rows.isEmpty()) {
          AutoRuleAuditMapper.Row r = rows.getFirst();
          changedAt = Ops.time(r.createdAt);
          changedBy = r.adminId == null ? r.adminName : names.of(List.of(r.adminId)).getOrDefault(r.adminId, r.adminName);
        }
      }
      ReviewView s = view(d.summary());
      return new ReviewDetailView(s.phase(), s.outcome(), s.failed(), s.decidedAt(), d.masterEnabled(),
          d.rules().stream().map(r -> new RuleResultView(r.code(), r.status(), r.values())).toList(), d.configVersion(), changedAt, changedBy, view(d.precheck()));
    }).orElse(null);
  }
}
