package com.forecast.app.admin.security;

import com.forecast.app.common.error.BizException;
import com.forecast.app.common.error.ValidationException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * 审计日志（后台方案第 2 节，修复现有系统 S5，AC-34）：所有写操作都记，失败也记。
 * <ul>
 *   <li>字段名含 password、secret、token、privateKey 的值一律记成 {@code ***}，任何层级都过滤。</li>
 *   <li>在独立的事务里写：操作失败回滚时，失败记录照样留下。</li>
 *   <li>只增不改，没有修改和删除的接口。</li>
 * </ul>
 */
@Service
public class Audit {

  /** 操作结果和修改前后的值（只放要记的字段） */
  public record Change<T>(T result, Map<String, Object> before, Map<String, Object> after) {

    public static <T> Change<T> of(T result, Map<String, Object> before, Map<String, Object> after) {
      return new Change<>(result, before, after);
    }
  }

  static final String MASK = "***";
  private static final Pattern SECRET = Pattern.compile("(?i).*(password|secret|token|privatekey|private_key).*");

  private final SecurityMapper mapper;
  private final ObjectMapper json;
  private final TransactionTemplate own;
  private final Clock clock;

  Audit(SecurityMapper mapper, ObjectMapper json, PlatformTransactionManager tm, Clock clock) {
    this.mapper = mapper;
    this.json = json;
    this.own = new TransactionTemplate(tm);
    this.own.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.clock = clock;
  }

  /** 高危操作必须填原因（后台方案 G4），最多 500 字 */
  public static String requireReason(String reason) {
    if (reason == null || reason.isBlank()) throw new ValidationException(List.of(new ValidationException.Field("reason", "required")));
    if (reason.strip().length() > 500) throw new ValidationException(List.of(new ValidationException.Field("reason", "tooLong")));
    return reason.strip();
  }

  /**
   * 执行一个后台写操作并记审计：成功记前后的值；业务错误、字段错误记失败（原因写错误码）后原样抛出。
   *
   * @param reason 高危操作的原因（调用方已经校验）；普通操作可以为空
   */
  public <T> T run(String action, String target, boolean highRisk, String reason, Supplier<Change<T>> op) {
    Change<T> c;
    try {
      c = op.get();
    } catch (BizException e) {
      write(action, target, highRisk, "FAILURE", join(reason, e.code()), null, null);
      throw e;
    } catch (ValidationException e) {
      write(action, target, highRisk, "FAILURE", join(reason, "VALIDATION_FAILED"), null, null);
      throw e;
    }
    write(action, target, highRisk, "SUCCESS", reason, c.before(), c.after());
    return c.result();
  }

  /** 直接记一条（登录、退出等） */
  public void record(String action, String target, boolean highRisk, String result, String reason, Map<String, Object> before, Map<String, Object> after) {
    write(action, target, highRisk, result, reason, before, after);
  }

  private static String join(String reason, String code) {
    return reason == null || reason.isBlank() ? code : reason + "（失败：" + code + "）";
  }

  void write(String action, String target, boolean highRisk, String result, String reason, Map<String, Object> before, Map<String, Object> after) {
    var ctx = AdminContext.find();
    SecurityMapper.AuditRow r = new SecurityMapper.AuditRow();
    r.adminId = ctx.map(AdminContext::adminId).orElse(null);
    r.adminName = ctx.map(AdminContext::username).orElse(target == null || target.isBlank() ? "-" : cut(target, 40));
    r.ip = AdminContext.requestIp();
    r.action = action;
    r.target = target == null ? "" : cut(target, 200);
    r.highRisk = highRisk;
    r.result = result;
    r.reason = reason == null ? null : cut(reason, 500);
    r.beforeJson = before == null ? null : json.writeValueAsString(scrub(before));
    r.afterJson = after == null ? null : json.writeValueAsString(scrub(after));
    r.createdAt = LocalDateTime.now(clock);
    own.executeWithoutResult(st -> mapper.insertAudit(r));
  }

  /** 去掉密码等字段的值（任何层级） */
  @SuppressWarnings("unchecked")
  static Map<String, Object> scrub(Map<String, Object> m) {
    Map<String, Object> out = new LinkedHashMap<>();
    m.forEach((k, v) -> {
      if (k != null && SECRET.matcher(k).matches()) out.put(k, MASK);
      else if (v instanceof Map<?, ?> sub) out.put(k, scrub((Map<String, Object>) sub));
      else out.put(k, v);
    });
    return out;
  }

  private static String cut(String s, int n) {
    return s.length() <= n ? s : s.substring(0, n);
  }
}
