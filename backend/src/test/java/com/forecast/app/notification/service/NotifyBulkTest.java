package com.forecast.app.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.forecast.app.notification.api.NotifyApi;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 批量通知 {@link NotifyApi#toUsers}（forecast S5：结算、退款一次几千人）和逐个 {@link NotifyApi#toUser} <b>写出来的完全一样</b>：
 * 两组情况相同的客户（推送开 / 关、有没有订阅设备、一个客户两台设备、邮件开 / 关、英文偏好），一组逐个发、一组批量发，
 * 比较站内信、每个渠道的通知记录（状态、跳过原因、标题）、推送和邮件任务的内容（除了各自的 id）。业务回滚时同样不发。
 */
@SpringBootTest
@ActiveProfiles("test")
class NotifyBulkTest {

  static final AtomicLong IDS = new AtomicLong(System.currentTimeMillis() * 1000 + 900_000_000L);

  @Autowired NotifyApi notify;
  @Autowired JdbcTemplate jdbc;
  @Autowired TransactionTemplate tx;
  final ObjectMapper json = new ObjectMapper();

  /** 一种情况的客户：push / email 开关、设备数、语言 */
  long customer(boolean push, boolean email, int devices, String lang) {
    long id = IDS.incrementAndGet();
    jdbc.update("INSERT INTO notify_pref (user_id, push_enabled, email_enabled, lang) VALUES (?, ?, ?, ?)", id, push, email, lang);
    for (int i = 0; i < devices; i++) {
      String ep = "http://localhost:1/push/" + id + "-" + i;
      jdbc.update("INSERT INTO notify_push_sub (user_id, endpoint, endpoint_hash, p256dh, auth, created_at) VALUES (?, ?, SHA2(?, 256), 'k', 'a', UTC_TIMESTAMP(3))", id,
          ep, ep);
    }
    return id;
  }

  static final Object[][] CASES = {{true, true, 1, "zh-CN"}, {true, true, 0, "zh-CN"}, {false, true, 1, "en"}, {true, false, 2, "en"}, {false, false, 0, "zh-CN"}};

  List<Long> group() {
    List<Long> out = new ArrayList<>();
    for (Object[] c : CASES) out.add(customer((Boolean) c[0], (Boolean) c[1], (Integer) c[2], (String) c[3]));
    return out;
  }

  static Map<String, String> params(int i) {
    return Map.of("market", "市场" + i, "marketEn", "Market " + i, "refundKind", "CANCELLED", "amount", i + ".00", "refundReason", "NO_WINNER");
  }

  /** 一个客户收到的全部东西（不含各自的 id、时间）：站内信、通知记录、任务 */
  String received(long uid) {
    List<String> msgs = jdbc.queryForList("SELECT CONCAT(kind, '|', template_code, '|', params) FROM notify_message WHERE user_id = ? ORDER BY id", String.class, uid);
    List<String> recs = jdbc.queryForList("SELECT CONCAT(channel, '|', status, '|', COALESCE(error, '-'), '|', title, '|', source) FROM notify_record WHERE user_id = ? "
        + "ORDER BY channel, status, id", String.class, uid);
    List<String> tasks = new ArrayList<>();
    for (Map<String, Object> t : jdbc.queryForList("SELECT task_type, payload, max_attempts FROM common_task WHERE JSON_EXTRACT(payload, '$.userId') = ? "
        + "ORDER BY task_type, id", uid)) {
      JsonNode p = json.readTree((String) t.get("payload"));
      Map<String, String> m = new TreeMap<>();
      p.propertyNames().forEach(k -> {
        if (!k.equals("recordId") && !k.equals("messageId") && !k.equals("subId") && !k.equals("userId")) m.put(k, p.get(k).toString());
      });
      m.put("hasRecordId", String.valueOf(p.hasNonNull("recordId")));
      if (p.has("messageId")) m.put("hasMessageId", String.valueOf(p.hasNonNull("messageId")));
      if (p.has("subId")) m.put("hasSubId", String.valueOf(p.hasNonNull("subId")));
      tasks.add(t.get("task_type") + "|" + t.get("max_attempts") + "|" + m);
    }
    return "messages=" + msgs + "\nrecords=" + recs + "\ntasks=" + tasks;
  }

  @Test
  void 批量发送和逐个发送写出来的完全一样_站内信_每个渠道的记录_推送和邮件任务() {
    List<Long> one = group();
    List<Long> bulk = group();
    tx.executeWithoutResult(st -> {
      for (int i = 0; i < one.size(); i++) notify.toUser(one.get(i), "MARKET_REFUNDED", params(i));
    });
    List<NotifyApi.Notice> notices = new ArrayList<>();
    for (int i = 0; i < bulk.size(); i++) notices.add(new NotifyApi.Notice(bulk.get(i), params(i)));
    tx.executeWithoutResult(st -> notify.toUsers("MARKET_REFUNDED", notices));
    for (int i = 0; i < one.size(); i++) {
      assertThat(received(bulk.get(i))).as("情况 " + i).isEqualTo(received(one.get(i)));
    }
    // 情况 0：站内信 + 推送 1 台 + 邮件；情况 3：推送 2 台、邮件关了
    assertThat(received(bulk.get(0))).contains("INBOX|SENT").contains("PUSH|PENDING").contains("EMAIL|PENDING");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM common_task WHERE task_type = 'PUSH_SEND' AND JSON_EXTRACT(payload, '$.userId') = ?", Integer.class, bulk.get(3)))
        .isEqualTo(2);
    assertThat(received(bulk.get(0))).as("任务确实查到了（不是两边都空）").contains("tasks=[MAIL_SEND|5|").contains("PUSH_SEND|5|");
    assertThat(received(bulk.get(4))).contains("PUSH|SKIPPED|客户关闭了推送").contains("EMAIL|SKIPPED|客户关闭了邮件");
    // 推送任务里的站内信编号是这个客户的那条
    String payload = jdbc.queryForObject("SELECT payload FROM common_task WHERE task_type = 'PUSH_SEND' AND JSON_EXTRACT(payload, '$.userId') = ?", String.class, bulk.get(0));
    long messageId = json.readTree(payload).get("messageId").asLong();
    assertThat(jdbc.queryForObject("SELECT user_id FROM notify_message WHERE id = ?", Long.class, messageId)).isEqualTo(bulk.get(0));
  }

  @Test
  void 超过一批的客户_同一个客户两条_业务回滚就不发_模板停用不发() {
    List<NotifyApi.Notice> many = new ArrayList<>();
    long first = IDS.get() + 1;
    for (int i = 0; i < 1_203; i++) many.add(new NotifyApi.Notice(customer(true, false, 0, "zh-CN"), params(i)));
    long twice = customer(true, true, 0, "en");
    many.add(new NotifyApi.Notice(twice, params(1)));
    many.add(new NotifyApi.Notice(twice, params(2)));
    tx.executeWithoutResult(st -> notify.toUsers("LATE_BET_REFUNDED", many));
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notify_message WHERE template_code = 'LATE_BET_REFUNDED' AND user_id BETWEEN ? AND ?", Integer.class, first,
        twice)).isEqualTo(1_205);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notify_message WHERE user_id = ?", Integer.class, twice)).isEqualTo(2);
    long rolled = customer(true, true, 0, "zh-CN");
    assertThatThrownBy(() -> tx.executeWithoutResult(st -> {
      notify.toUsers("LATE_BET_REFUNDED", List.of(new NotifyApi.Notice(rolled, params(3))));
      throw new IllegalStateException("业务失败");
    })).hasMessage("业务失败");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notify_message WHERE user_id = ?", Integer.class, rolled)).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notify_record WHERE user_id = ?", Integer.class, rolled)).isZero();
    jdbc.update("UPDATE notify_template SET enabled = 0 WHERE code = 'LATE_BET_REFUNDED'");
    try {
      notify.toUsers("LATE_BET_REFUNDED", List.of(new NotifyApi.Notice(rolled, params(3))));
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notify_message WHERE user_id = ?", Integer.class, rolled)).isZero();
    } finally {
      jdbc.update("UPDATE notify_template SET enabled = 1 WHERE code = 'LATE_BET_REFUNDED'");
    }
    assertThatThrownBy(() -> notify.toUsers("POOL_RECON_DIFF", List.of(new NotifyApi.Notice(rolled, Map.of())))).isInstanceOf(IllegalArgumentException.class);
  }
}
