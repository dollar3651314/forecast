package com.forecast.app.notification.service;

import com.forecast.app.common.e2e.E2eGuard;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 端到端测试的邮件替身（S19，只在 profile e2e 下存在）：代替 SMTP 收下邮件，放在内存里（每个邮箱最近 20 封）。
 * 邮件仍然走真实的通知链路（模板 → 邮件任务 → {@link NotifyWorker}），只是最后一步不连邮件服务商。
 * {@code GET /api/e2e/mail/code?email=} 取这个邮箱最近一封验证码邮件里的验证码，没有返回 404。
 */
@Primary
@Component
@Profile(E2eGuard.PROFILE)
class E2eMailbox implements MailTransport {

  record Mail(String to, String subject, String body, Instant at) {}

  private static final Pattern CODE = Pattern.compile("验证码是 (\\d{6})");
  private final Map<String, Deque<Mail>> boxes = new ConcurrentHashMap<>();

  @Override
  public void send(String to, String subject, String body) {
    Deque<Mail> box = boxes.computeIfAbsent(to.toLowerCase(Locale.ROOT), k -> new ArrayDeque<>());
    synchronized (box) {
      box.addFirst(new Mail(to, subject, body, Instant.now()));
      while (box.size() > 20) box.removeLast();
    }
  }

  List<Mail> mails(String email) {
    Deque<Mail> box = boxes.get(email.toLowerCase(Locale.ROOT));
    if (box == null) return List.of();
    synchronized (box) {
      return List.copyOf(box);
    }
  }

  @RestController
  @Profile(E2eGuard.PROFILE)
  static class Api {

    private final E2eMailbox box;

    Api(E2eMailbox box) {
      this.box = box;
    }

    /** 最近一封验证码邮件里的验证码 */
    @GetMapping("/api/e2e/mail/code")
    ResponseEntity<Map<String, String>> code(@RequestParam String email) {
      for (Mail m : box.mails(email)) {
        Matcher x = CODE.matcher(m.body());
        if (x.find()) return ResponseEntity.ok(Map.of("code", x.group(1), "at", m.at().toString()));
      }
      return ResponseEntity.notFound().build();
    }

    /** 这个邮箱收到的全部邮件的标题（最近的在前），用来检查通知邮件 */
    @GetMapping("/api/e2e/mail/subjects")
    List<String> subjects(@RequestParam String email) {
      return box.mails(email).stream().map(Mail::subject).toList();
    }

    /** 最近一封标题包含 subject 的邮件（标题和正文；forecast S11：开赛提醒邮件里的链接和退订链接）；没有时 404 */
    @GetMapping("/api/e2e/mail/last")
    ResponseEntity<Map<String, String>> last(@RequestParam String email, @RequestParam(defaultValue = "") String subject) {
      for (Mail m : box.mails(email)) {
        if (m.subject().contains(subject)) return ResponseEntity.ok(Map.of("subject", m.subject(), "body", m.body(), "at", m.at().toString()));
      }
      return ResponseEntity.notFound().build();
    }
  }
}
