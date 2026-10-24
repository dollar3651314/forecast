package com.forecast.app.account.auth;

import com.forecast.app.account.auth.Rows.EmailCodeRow;
import com.forecast.app.common.error.BizException;
import com.forecast.app.common.error.RetryLaterException;
import com.forecast.app.notification.api.MailApi;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 邮箱验证码：6 位数字，10 分钟有效，60 秒内不能重发，最多试 5 次，用过即作废。库里只存哈希。
 * 同一邮箱同一用途 24 小时内最多发 {@code codeDailyLimit} 次（安全审查 SEC-03）：每个验证码只能试 5 次，但不限次数地换新验证码就能一直猜下去，
 * 重置密码的验证码猜中就能接管账号。按 IP 的限制在 {@link SendCodeLimiter}。
 */
@Service
class EmailCodeService {

  enum Purpose { REGISTER, RESET_PASSWORD }

  private final AuthMapper mapper;
  private final MailApi mail;
  private final AuthProperties props;

  EmailCodeService(AuthMapper mapper, MailApi mail, AuthProperties props) {
    this.mapper = mapper;
    this.mail = mail;
    this.props = props;
  }

  private static String hash(String email, Purpose purpose, String code) {
    return Secrets.sha256(email + ":" + purpose + ":" + code);
  }

  /**
   * 发送验证码。60 秒内重发抛 {@link RetryLaterException}（429，带剩余秒数）。
   *
   * @return 下次可以重发前要等的秒数（客户端按它倒计时）
   */
  @Transactional
  int send(String email, Purpose purpose) {
    EmailCodeRow last = mapper.latestEmailCodeForSend(email, purpose.name());
    if (last != null && last.ageSeconds() < props.codeResendSeconds()) {
      throw new RetryLaterException("CODE_TOO_FREQUENT", "发送太频繁，请稍后再试", props.codeResendSeconds() - last.ageSeconds());
    }
    // 同一邮箱同一用途 24 小时的上限（安全审查 SEC-03）：没有明确的等待时间，不带 cooldownSeconds
    if (mapper.countEmailCodesSince(email, purpose.name(), 24) >= props.codeDailyLimit()) throw new BizException("CODE_TOO_FREQUENT", "发送太频繁，请稍后再试");
    String code = Secrets.newCode();
    mapper.insertEmailCode(email, purpose.name(), hash(email, purpose, code), props.codeTtlMinutes());
    mail.send(email, "EMAIL_CODE", Map.of("code", code, "purpose", purpose.name()));
    return props.codeResendSeconds();
  }

  /** 两次发送之间的冷却秒数（配置值） */
  int cooldownSeconds() {
    return props.codeResendSeconds();
  }

  /**
   * 校验并作废验证码。单独提交（REQUIRES_NEW）：校验失败时尝试次数也要记下来，不能随调用方的事务回滚。
   *
   * @return 是否通过
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  boolean verifyAndConsume(String email, Purpose purpose, String code) {
    EmailCodeRow row = mapper.latestEmailCode(email, purpose.name());
    if (row == null || row.expired() || row.consumed() || row.attempts() >= props.codeMaxAttempts()) return false;
    if (code == null || !hash(email, purpose, code.trim()).equals(row.codeHash())) {
      mapper.incrementCodeAttempts(row.id());
      return false;
    }
    return mapper.consumeCode(row.id()) == 1;
  }
}
