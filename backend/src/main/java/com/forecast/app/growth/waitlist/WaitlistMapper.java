package com.forecast.app.growth.waitlist;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** growth_waitlist、growth_launch 的读写（forecast S16，MF-8），见 resources/mapper/growth/WaitlistMapper.xml。只有 growth 模块使用。 */
@Mapper
public interface WaitlistMapper {

  /** growth_waitlist 的一行 */
  class Row {
    public long id;
    public String email;
    public String lang;
    public String status;
    public String confirmToken;
    public LocalDateTime confirmSentAt;
    public int confirmSends;
    public String unsubToken;
    public String src;
    public String cmp;
    public String ref;
    public String startParam;
    public LocalDateTime createdAt;
    public LocalDateTime confirmedAt;
    public LocalDateTime notifiedAt;
    public LocalDateTime unsubscribedAt;
  }

  /** growth_launch */
  class Launch {
    public String status;
    public long adminId;
    public String reason;
    public int recipients;
    public int sent;
    public LocalDateTime createdAt;
    public LocalDateTime finishedAt;
  }

  /** 同一邮箱已经有一行时不插入（唯一键兜底，不做"查不到就加锁"的读，约定 34） */
  int insertIgnore(@Param("email") String email, @Param("lang") String lang, @Param("confirmToken") String confirmToken,
      @Param("unsubToken") String unsubToken, @Param("src") String src, @Param("cmp") String cmp, @Param("ref") String ref, @Param("start") String start,
      @Param("ipHash") String ipHash, @Param("now") LocalDateTime now);

  Row byEmail(@Param("email") String email);

  Row byConfirmToken(@Param("token") String token);

  Row byUnsubToken(@Param("token") String token);

  Row byId(@Param("id") long id);

  /** 待确认的再发一封确认邮件：带旧的发送次数做条件更新（同时两次提交只发一封） */
  int resend(@Param("id") long id, @Param("sends") int sends, @Param("lang") String lang, @Param("now") LocalDateTime now);

  /** 退订过的重新订阅：回到待确认，换新的确认串，发送次数从 1 算 */
  int resubscribe(@Param("id") long id, @Param("confirmToken") String confirmToken, @Param("lang") String lang, @Param("src") String src, @Param("cmp") String cmp,
      @Param("ref") String ref, @Param("start") String start, @Param("now") LocalDateTime now);

  /** 待确认 → 已确认 */
  int confirm(@Param("id") long id, @Param("now") LocalDateTime now);

  /** 待确认 / 已确认 / 已通知 → 已退订 */
  int unsubscribe(@Param("id") long id, @Param("now") LocalDateTime now);

  /** 已确认 → 已通知（上线通知，和写邮件任务同一个事务） */
  int markNotified(@Param("id") long id, @Param("now") LocalDateTime now);

  /** 还没发上线通知的已确认订阅（id 升序） */
  List<Long> confirmedIds(@Param("afterId") long afterId, @Param("limit") int limit);

  long countByStatus(@Param("status") String status);

  List<Row> list(@Param("status") String status, @Param("offset") int offset, @Param("limit") int limit);

  long count(@Param("status") String status);

  // ---------- 上线通知 ----------

  Launch launch();

  /** 只有一行（主键 1）：已经有了就不插入（返回 0 = 已经发过） */
  int insertLaunch(@Param("adminId") long adminId, @Param("reason") String reason, @Param("recipients") int recipients, @Param("now") LocalDateTime now);

  int addSent(@Param("n") int n);

  int finishLaunch(@Param("now") LocalDateTime now);

  /** 只给端到端测试（E2eWaitlist）：删掉上线通知，让同一个库里的用例能重跑 */
  int deleteLaunchForE2e();
}
