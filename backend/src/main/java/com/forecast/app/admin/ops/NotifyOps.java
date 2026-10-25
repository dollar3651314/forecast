package com.forecast.app.admin.ops;

import com.forecast.app.account.api.CustomerAdminApi;
import com.forecast.app.admin.security.AdminContext;
import com.forecast.app.admin.security.AdminNames;
import com.forecast.app.admin.security.Audit;
import com.forecast.app.common.error.BizException;
import com.forecast.app.common.error.ValidationException;
import com.forecast.app.notification.api.NotifyAdminApi;
import com.forecast.app.notification.api.NotifyChannel;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 后台"通知"（S16b）：模板（只改文案，内置模板不能停用）、手动发送给单个客户（高危）、通知记录。 */
@Service
class NotifyOps {

  /**
   * 字段和后台 NotifyTemplate 一致（id 就是模板编号）。S9：audience（user / any / staff）、繁体 titleTw / bodyTw、需求第 7 节编号 reqNo、
   * 员工模板的收件人权限点 staffPerms（为空 = 发告警邮箱）。
   */
  record TemplateView(String id, String code, String audience, String reqNo, String nameZh, String nameEn, boolean builtIn, boolean enabled,
      List<String> channels, String titleZh, String bodyZh, String titleEn, String bodyEn, String titleTw, String bodyTw, List<String> variables,
      List<String> staffPerms, String updatedAt) {}

  record TemplatePatch(String titleZh, String bodyZh, String titleEn, String bodyEn, String titleTw, String bodyTw, Boolean enabled) {}

  /** 字段和原型 ManualNotifyInput 一致 */
  record ManualInput(Long customerId, List<String> channels, String title, String body, String reason) {}

  /** 字段和原型 NotifyRecord 一致；发给风控人员、按邮箱发的没有客户号，customerName 是收件人 */
  record RecordView(String id, Long customerId, String customerName, String source, String templateCode, String channel, String title, String status,
      String error, String operator, String time) {}

  private final NotifyAdminApi notify;
  private final CustomerAdminApi customers;
  private final AdminNames names;
  private final Audit audit;

  NotifyOps(NotifyAdminApi notify, CustomerAdminApi customers, AdminNames names, Audit audit) {
    this.notify = notify;
    this.customers = customers;
    this.names = names;
    this.audit = audit;
  }

  private static TemplateView view(NotifyAdminApi.Template t) {
    return new TemplateView(t.code(), t.code(), t.audience().toLowerCase(), t.reqNo(), t.nameZh(), t.nameEn(), t.builtIn(), t.enabled(),
        t.channels().stream().map(c -> c.name().toLowerCase()).toList(), t.titleZh(), t.bodyZh(), t.titleEn(), t.bodyEn(), t.titleTw(), t.bodyTw(), t.variables(),
        t.staffPerms(), Ops.time(t.updatedAt()));
  }

  List<TemplateView> templates() {
    return notify.templates().stream().map(NotifyOps::view).toList();
  }

  TemplateView updateTemplate(String code, TemplatePatch p) {
    TemplatePatch patch = p == null ? new TemplatePatch(null, null, null, null, null, null, null) : p;
    Map<String, Object> before = new LinkedHashMap<>();
    notify.templates().stream().filter(t -> t.code().equals(code)).findFirst().ifPresent(t -> {
      before.put("titleZh", t.titleZh());
      before.put("bodyZh", t.bodyZh());
      before.put("titleEn", t.titleEn());
      before.put("bodyEn", t.bodyEn());
      before.put("titleTw", t.titleTw());
      before.put("bodyTw", t.bodyTw());
      before.put("enabled", t.enabled());
    });
    return audit.run("notify.template_update", code, false, null, () -> {
      NotifyAdminApi.Template t = notify.updateTemplate(code, patch.titleZh(), patch.bodyZh(), patch.titleEn(), patch.bodyEn(), patch.titleTw(), patch.bodyTw(),
          patch.enabled());
      Map<String, Object> after = new LinkedHashMap<>();
      after.put("titleZh", t.titleZh());
      after.put("bodyZh", t.bodyZh());
      after.put("titleEn", t.titleEn());
      after.put("bodyEn", t.bodyEn());
      after.put("titleTw", t.titleTw());
      after.put("bodyTw", t.bodyTw());
      after.put("enabled", t.enabled());
      return Audit.Change.of(view(t), before, after);
    });
  }

  /** 手动发送给单个客户（高危，必须填原因）；推送和邮件仍然遵守客户的通知偏好 */
  @Transactional
  void send(AdminContext actor, ManualInput in) {
    ManualInput input = in == null ? new ManualInput(null, null, null, null, null) : in;
    String r = Audit.requireReason(input.reason());
    if (input.customerId() == null) throw new ValidationException(List.of(new ValidationException.Field("customerId", "required")));
    Set<NotifyChannel> chs = EnumSet.noneOf(NotifyChannel.class);
    for (String c : input.channels() == null ? List.<String>of() : input.channels()) {
      try {
        chs.add(NotifyChannel.valueOf(c.toUpperCase()));
      } catch (IllegalArgumentException e) {
        throw new ValidationException(List.of(new ValidationException.Field("channels", "invalid")));
      }
    }
    audit.run("notify.manual_send", String.valueOf(input.customerId()), true, r, () -> {
      customers.customer(input.customerId()).orElseThrow(() -> new BizException("NOT_FOUND", "客户不存在"));
      notify.manual(input.customerId(), chs, input.title(), input.body(), actor.adminId());
      return Audit.Change.of(null, null, Map.of("channels", chs.stream().map(c -> c.name().toLowerCase()).toList(), "title",
          input.title() == null ? "" : input.title()));
    });
  }

  @Transactional(readOnly = true)
  Ops.Page<RecordView> records(String channel, String status, String keyword, int page, int pageSize) {
    Ops.Slice s = Ops.slice(page, pageSize);
    NotifyChannel ch = null;
    if (Ops.blank(channel) != null) {
      try {
        ch = NotifyChannel.valueOf(channel.strip().toUpperCase());
      } catch (IllegalArgumentException e) {
        throw new ValidationException(List.of(new ValidationException.Field("channel", "invalid")));
      }
    }
    String kw = Ops.blank(keyword);
    List<Long> users = kw == null ? null : customers.search(kw, 500);
    var p = notify.records(ch, Ops.blank(status) == null ? null : status.strip().toUpperCase(), users, s.offset(), s.size());
    var cs = customers.customers(p.items().stream().map(NotifyAdminApi.Record::userId).filter(Objects::nonNull).toList());
    Map<Long, String> ops = names.of(p.items().stream().map(NotifyAdminApi.Record::operatorId).filter(Objects::nonNull).toList());
    return new Ops.Page<>(p.items().stream().map(r -> {
      var c = r.userId() == null ? null : cs.get(r.userId());
      return new RecordView(String.valueOf(r.id()), r.userId(), c != null ? c.name() : r.recipient(), r.source().toLowerCase(), r.templateCode(),
          r.channel().name().toLowerCase(), r.title(), r.status().toLowerCase(), r.error(), r.operatorId() == null ? null : ops.get(r.operatorId()),
          Ops.time(r.createdAt()));
    }).toList(), p.total(), s.page(), s.size());
  }
}
