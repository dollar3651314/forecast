package com.forecast.app.admin.system;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 管理员、角色、菜单的维护（admin_* 表）。只有 admin 模块使用。 */
@Mapper
interface SystemMapper {

  class AdminRow {
    Long id;
    String username;
    String displayName;
    String email;
    String passwordHash;
    String status;
    boolean mustChangePassword;
    LocalDateTime lastLoginAt;
    LocalDateTime createdAt;
  }

  class RoleRow {
    Long id;
    String code;
    String nameZh;
    String nameEn;
    String description;
    boolean builtIn;
  }

  class MenuRow {
    String menuKey;
    int sortOrder;
    boolean visible;
  }

  List<AdminRow> admins(@Param("keyword") String keyword, @Param("offset") int offset, @Param("limit") int limit);

  long countAdmins(@Param("keyword") String keyword);

  /** 加锁读（修改前） */
  AdminRow admin(@Param("id") long id);

  /** 不加锁读（列表、详情） */
  AdminRow adminForRead(@Param("id") long id);

  AdminRow adminByUsername(@Param("username") String username);

  void insertAdmin(AdminRow a);

  void updateAdmin(@Param("id") long id, @Param("displayName") String displayName, @Param("email") String email);

  void setStatus(@Param("id") long id, @Param("status") String status);

  void setPassword(@Param("id") long id, @Param("hash") String hash, @Param("mustChange") boolean mustChange);

  void revokeSessions(@Param("adminId") long adminId, @Param("now") LocalDateTime now);

  List<Long> roleIdsOf(@Param("adminId") long adminId);

  void clearRoles(@Param("adminId") long adminId);

  void addRole(@Param("adminId") long adminId, @Param("roleId") long roleId);

  /** 状态正常、有超级管理员角色的人数 */
  long countActiveSupers();

  List<RoleRow> roles();

  RoleRow role(@Param("id") long id);

  RoleRow roleByCode(@Param("code") String code);

  List<String> rolePermissions(@Param("roleId") long roleId);

  long memberCount(@Param("roleId") long roleId);

  void insertRole(RoleRow r);

  void updateRole(RoleRow r);

  void deleteRole(@Param("id") long id);

  void clearRolePermissions(@Param("roleId") long roleId);

  void addRolePermission(@Param("roleId") long roleId, @Param("permission") String permission);

  List<MenuRow> menus();

  void saveMenu(@Param("key") String key, @Param("order") int order, @Param("visible") boolean visible);
}
