package com.forecast.app.admin.security;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 后台登录、会话、权限查询、审计（admin_* 表）。只有 admin 模块使用。 */
@Mapper
interface SecurityMapper {

  class AdminRow {
    Long id;
    String username;
    String displayName;
    String email;
    String passwordHash;
    String status;
    boolean mustChangePassword;
    int failedCount;
    LocalDateTime lockedUntil;
  }

  class SessionRow {
    long id;
    long adminId;
    LocalDateTime lastSeenAt;
    LocalDateTime expiresAt;
    LocalDateTime revokedAt;
  }

  AdminRow byUsername(@Param("username") String username);

  AdminRow byId(@Param("id") long id);

  long countAdmins();

  void insertAdmin(AdminRow a);

  void addRole(@Param("adminId") long adminId, @Param("roleId") long roleId);

  Long roleIdByCode(@Param("code") String code);

  void loginFailed(@Param("id") long id, @Param("failedCount") int failedCount, @Param("lockedUntil") LocalDateTime lockedUntil);

  void loginOk(@Param("id") long id, @Param("now") LocalDateTime now);

  void updatePassword(@Param("id") long id, @Param("hash") String hash, @Param("mustChange") boolean mustChange);

  void insertSession(@Param("adminId") long adminId, @Param("hash") String hash, @Param("ip") String ip, @Param("now") LocalDateTime now,
      @Param("expiresAt") LocalDateTime expiresAt);

  SessionRow session(@Param("hash") String hash);

  void touchSession(@Param("id") long id, @Param("now") LocalDateTime now);

  void revokeSession(@Param("id") long id, @Param("now") LocalDateTime now);

  void revokeAll(@Param("adminId") long adminId, @Param("now") LocalDateTime now);

  boolean isSuper(@Param("adminId") long adminId);

  List<String> permissions(@Param("adminId") long adminId);

  void insertAudit(AuditRow r);

  class AuditRow {
    Long id;
    Long adminId;
    String adminName;
    String ip;
    String action;
    String target;
    boolean highRisk;
    String result;
    String reason;
    String beforeJson;
    String afterJson;
    LocalDateTime createdAt;
  }

  class NameRow {
    long id;
    String displayName;
  }

  List<NameRow> names(@Param("ids") java.util.Collection<Long> ids);

  /** 锁住管理员这一行（同一个管理员的调余额排队，当天累计额度不会被并发绕过） */
  Long lockAdmin(@Param("id") long id);
}
