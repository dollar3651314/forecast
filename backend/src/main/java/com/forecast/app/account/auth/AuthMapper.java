package com.forecast.app.account.auth;

import com.forecast.app.account.auth.Rows.EmailCodeRow;
import com.forecast.app.account.auth.Rows.Profile;
import com.forecast.app.account.auth.Rows.SessionRow;
import com.forecast.app.account.auth.Rows.UserRow;
import org.apache.ibatis.annotations.Mapper;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Param;

/** 账户模块的 SQL，见 resources/mapper/account/AuthMapper.xml。 */
@Mapper
interface AuthMapper {

  UserRow findUserByEmail(@Param("email") String email);

  UserRow findUserById(@Param("id") long id);

  /** 是不是员工账户；用户不存在时为 null（forecast S4） */
  Boolean isStaff(@Param("id") long id);

  /** 一批用户里 is_staff = 1 的 */
  java.util.List<Long> staffAmong(@Param("ids") java.util.Collection<Long> ids);

  int insertUser(UserInsert user);

  int insertProfile(Profile profile);

  int updatePassword(@Param("id") long id, @Param("passwordHash") String passwordHash);

  int updateStatus(@Param("id") long id, @Param("status") String status);

  int touchLogin(@Param("id") long id);

  /** 成功登录过（last_login_at 不为空；forecast S11 #23 订阅的条件 SQ-20；S16 起注册时的自动登录也记，SQ-27）；用户不存在时为 null */
  Boolean hasLoggedIn(@Param("id") long id);

  // ---------- 账号安全事件（v2 提现自动审批 R6） ----------
  int insertSecurityEvent(@Param("userId") long userId, @Param("type") String type, @Param("at") java.time.LocalDateTime at);

  /** (since, 现在] 里最近的一条；没有时为 null */
  com.forecast.app.account.api.SecurityEvent lastSecurityEventSince(@Param("userId") long userId, @Param("since") java.time.LocalDateTime since);

  /** 注册时间；用户不存在时为 null */
  java.time.LocalDateTime registeredAt(@Param("id") long id);

  int insertEmailCode(@Param("email") String email, @Param("purpose") String purpose, @Param("codeHash") String codeHash, @Param("ttlMinutes") int ttlMinutes);

  /** 发验证码时用，不加锁（见 XML 注释，压测 LT-1） */
  EmailCodeRow latestEmailCodeForSend(@Param("email") String email, @Param("purpose") String purpose);

  EmailCodeRow latestEmailCode(@Param("email") String email, @Param("purpose") String purpose);

  /** 最近 hours 小时内给这个邮箱发过几次这种用途的验证码 */
  int countEmailCodesSince(@Param("email") String email, @Param("purpose") String purpose, @Param("hours") int hours);

  int incrementCodeAttempts(@Param("id") long id);

  int consumeCode(@Param("id") long id);

  int insertSession(@Param("userId") long userId, @Param("tokenHash") String tokenHash, @Param("ip") String ip, @Param("userAgent") String userAgent, @Param("ttlHours") int ttlHours);

  SessionRow findActiveSession(@Param("tokenHash") String tokenHash);

  int extendSession(@Param("id") long id, @Param("ttlHours") int ttlHours);

  int revokeSession(@Param("tokenHash") String tokenHash);

  int revokeUserSessions(@Param("userId") long userId, @Param("exceptTokenHash") String exceptTokenHash);

  /** 插入用户时 MyBatis 回填 id */
  final class UserInsert {
    Long id;
    final String email;
    final String passwordHash;
    /** 勾选"已年满 18 周岁并同意服务条款"的时间（forecast S11） */
    LocalDateTime termsAcceptedAt;

    UserInsert(String email, String passwordHash) {
      this.email = email;
      this.passwordHash = passwordHash;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
  }
}
