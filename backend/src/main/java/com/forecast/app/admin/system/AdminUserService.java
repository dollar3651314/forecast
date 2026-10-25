package com.forecast.app.admin.system;

import com.forecast.app.admin.security.AdminContext;
import com.forecast.app.admin.security.Audit;
import com.forecast.app.admin.security.Passwords;
import com.forecast.app.admin.security.Permission;
import com.forecast.app.common.error.BizException;
import com.forecast.app.common.error.ValidationException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 管理员（后台方案第 2 节，修复现有系统 S4、C-10）：
 * <ul>
 *   <li><b>只有超级管理员能把超级管理员角色分配给别人</b>（新建、编辑都检查，AC-33）；任何人都不能改自己的角色。</li>
 *   <li>非超级管理员只能分配"权限点都在自己权限范围内"的角色，不能给别人超过自己的权限；也不能修改、停用、重置超级管理员。</li>
 *   <li>最后一个正常状态的超级管理员不能被停用、不能去掉超级管理员角色。</li>
 *   <li>新建和重置密码由系统生成临时密码，只在结果里返回一次，首次登录必须改；审计里不记密码（S5）。停用、重置后立即退出登录。</li>
 * </ul>
 */
@Service
class AdminUserService {

  private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_.-]{3,40}");
  private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");

  /** 字段和原型 AdminUser 一致 */
  record AdminView(long id, String username, String displayName, String email, List<String> roleIds, String status, boolean mustChangePassword,
      String lastLoginAt, String createdAt) {}

  record AdminInput(String username, String displayName, String email, List<String> roleIds) {}

  /** 新建、重置密码的结果：临时密码只在这里出现一次 */
  record TemporaryPassword(long id, String temporaryPassword) {}

  private final SystemMapper mapper;
  private final RoleService roles;
  private final Audit audit;
  private final Clock clock;

  AdminUserService(SystemMapper mapper, RoleService roles, Audit audit, Clock clock) {
    this.mapper = mapper;
    this.roles = roles;
    this.audit = audit;
    this.clock = clock;
  }

  private LocalDateTime now() {
    return LocalDateTime.now(clock);
  }

  static String time(LocalDateTime t) {
    return t == null ? null : t.toInstant(ZoneOffset.UTC).toString();
  }

  AdminView view(SystemMapper.AdminRow a) {
    return new AdminView(a.id, a.username, a.displayName, a.email, mapper.roleIdsOf(a.id).stream().map(String::valueOf).toList(),
        a.status.toLowerCase(), a.mustChangePassword, time(a.lastLoginAt), time(a.createdAt));
  }

  @Transactional(readOnly = true)
  Pages.Page<AdminView> list(String keyword, int page, int pageSize) {
    Pages.Slice s = Pages.slice(page, pageSize);
    String kw = keyword == null || keyword.isBlank() ? null : keyword.strip();
    return new Pages.Page<>(mapper.admins(kw, s.offset(), s.size()).stream().map(this::view).toList(), mapper.countAdmins(kw), s.page(), s.size());
  }

  @Transactional
  TemporaryPassword create(AdminContext actor, AdminInput in) {
    return audit.run("admin.create", in == null ? "" : String.valueOf(in.username()), true, null, () -> {
      AdminInput v = validate(in, true);
      if (mapper.adminByUsername(v.username()) != null) throw new BizException("USERNAME_TAKEN", "用户名已存在");
      Set<Long> roleIds = roleIds(v.roleIds());
      guardRoles(actor, Set.of(), roleIds);
      String temp = Passwords.temporary();
      SystemMapper.AdminRow a = new SystemMapper.AdminRow();
      a.username = v.username();
      a.displayName = v.displayName();
      a.email = v.email();
      a.passwordHash = Passwords.hash(temp);
      a.createdAt = now();
      mapper.insertAdmin(a);
      for (long r : roleIds) mapper.addRole(a.id, r);
      Map<String, Object> after = new LinkedHashMap<>();
      after.put("username", a.username);
      after.put("displayName", a.displayName);
      after.put("email", a.email);
      after.put("roleIds", ids(roleIds));
      after.put("password", "(临时密码)");
      return Audit.Change.of(new TemporaryPassword(a.id, temp), null, after);
    });
  }

  @Transactional
  void update(AdminContext actor, long id, AdminInput in) {
    audit.run("admin.update", String.valueOf(id), true, null, () -> {
      SystemMapper.AdminRow a = require(id);
      AdminInput v = validate(in, false);
      Set<Long> before = new LinkedHashSet<>(mapper.roleIdsOf(id));
      Set<Long> after = roleIds(v.roleIds());
      boolean targetSuper = roles.includesSuper(before);
      if (targetSuper && !actor.superAdmin()) throw new BizException("FORBIDDEN", "不能修改超级管理员");
      if (!before.equals(after)) {
        if (id == actor.adminId()) throw new BizException("CANNOT_EDIT_OWN_ROLES", "不能修改自己的角色");
        guardRoles(actor, before, after);
        if (targetSuper && !roles.includesSuper(after) && "ACTIVE".equals(a.status) && mapper.countActiveSupers() <= 1) {
          throw new BizException("LAST_SUPER_ADMIN", "至少要保留一个超级管理员");
        }
        mapper.clearRoles(id);
        for (long r : after) mapper.addRole(id, r);
      }
      mapper.updateAdmin(id, v.displayName(), v.email());
      return Audit.Change.of(null, Map.of("displayName", a.displayName, "email", a.email, "roleIds", ids(before)),
          Map.of("displayName", v.displayName(), "email", v.email(), "roleIds", ids(after)));
    });
  }

  @Transactional
  void setStatus(AdminContext actor, long id, boolean active, String reason) {
    String r = Audit.requireReason(reason);
    audit.run(active ? "admin.enable" : "admin.disable", String.valueOf(id), true, r, () -> {
      SystemMapper.AdminRow a = require(id);
      if (id == actor.adminId()) throw new BizException("CANNOT_DISABLE_SELF", "不能停用自己");
      boolean targetSuper = roles.includesSuper(mapper.roleIdsOf(id));
      if (targetSuper && !actor.superAdmin()) throw new BizException("FORBIDDEN", "不能修改超级管理员");
      if (!active && targetSuper && "ACTIVE".equals(a.status) && mapper.countActiveSupers() <= 1) {
        throw new BizException("LAST_SUPER_ADMIN", "至少要保留一个超级管理员");
      }
      mapper.setStatus(id, active ? "ACTIVE" : "DISABLED");
      if (!active) mapper.revokeSessions(id, now());
      return Audit.Change.of(null, Map.of("status", a.status.toLowerCase()), Map.of("status", active ? "active" : "disabled"));
    });
  }

  @Transactional
  TemporaryPassword resetPassword(AdminContext actor, long id, String reason) {
    String r = Audit.requireReason(reason);
    return audit.run("admin.reset_password", String.valueOf(id), true, r, () -> {
      require(id);
      if (roles.includesSuper(mapper.roleIdsOf(id)) && !actor.superAdmin()) throw new BizException("FORBIDDEN", "不能重置超级管理员的密码");
      // 安全审查 SEC-04：重置后拿到临时密码就能登录成对方，所以对方的权限点必须都在自己的范围内（和分配角色的规则一致）
      if (!actor.superAdmin()) {
        for (long roleId : mapper.roleIdsOf(id)) {
          for (Permission p : roles.permissionsOf(roleId)) {
            if (!actor.has(p)) throw new BizException("PERMISSION_EXCEEDS_OWN", "不能重置权限超出自己的管理员的密码");
          }
        }
      }
      String temp = Passwords.temporary();
      mapper.setPassword(id, Passwords.hash(temp), true);
      mapper.revokeSessions(id, now());
      return Audit.Change.of(new TemporaryPassword(id, temp), Map.of("mustChangePassword", false, "password", "-"),
          Map.of("mustChangePassword", true, "password", "-"));
    });
  }

  @Transactional(readOnly = true)
  AdminView get(long id) {
    SystemMapper.AdminRow a = mapper.adminForRead(id);
    if (a == null) throw new BizException("NOT_FOUND", "管理员不存在");
    return view(a);
  }

  private SystemMapper.AdminRow require(long id) {
    SystemMapper.AdminRow a = mapper.admin(id);
    if (a == null) throw new BizException("NOT_FOUND", "管理员不存在");
    return a;
  }

  /**
   * 角色分配检查：新增的角色里有超级管理员时只有超级管理员能分配（S4）；非超级管理员新增的角色，权限点必须都在自己的权限范围内。
   */
  private void guardRoles(AdminContext actor, Set<Long> before, Set<Long> after) {
    Set<Long> added = new LinkedHashSet<>(after);
    added.removeAll(before);
    if (added.isEmpty() || actor.superAdmin()) return;
    if (roles.includesSuper(added)) throw new BizException("CANNOT_ASSIGN_SUPER", "只有超级管理员能分配超级管理员角色");
    for (long r : added) {
      for (Permission p : roles.permissionsOf(r)) {
        if (!actor.has(p)) throw new BizException("PERMISSION_EXCEEDS_OWN", "不能分配超出自己权限的角色");
      }
    }
  }

  private Set<Long> roleIds(List<String> in) {
    Set<Long> out = new LinkedHashSet<>();
    if (in == null) return out;
    for (String s : in) {
      long id;
      try {
        id = Long.parseLong(s);
      } catch (NumberFormatException e) {
        throw new ValidationException(List.of(new ValidationException.Field("roleIds", "invalid")));
      }
      if (mapper.role(id) == null) throw new ValidationException(List.of(new ValidationException.Field("roleIds", "invalid")));
      out.add(id);
    }
    return out;
  }

  private static List<String> ids(Set<Long> s) {
    return s.stream().sorted().map(String::valueOf).toList();
  }

  private static AdminInput validate(AdminInput in, boolean creating) {
    List<ValidationException.Field> errors = new ArrayList<>();
    if (in == null) in = new AdminInput(null, null, null, null);
    String username = in.username() == null ? null : in.username().strip();
    if (creating && (username == null || !USERNAME.matcher(username).matches())) errors.add(new ValidationException.Field("username", "invalid"));
    String name = in.displayName() == null ? "" : in.displayName().strip();
    if (name.isEmpty() || name.length() > 60) errors.add(new ValidationException.Field("displayName", name.isEmpty() ? "required" : "tooLong"));
    String email = in.email() == null ? "" : in.email().strip();
    if (email.length() > 254 || !EMAIL.matcher(email).matches()) errors.add(new ValidationException.Field("email", "invalid"));
    if (!errors.isEmpty()) throw new ValidationException(errors);
    return new AdminInput(username, name, email, in.roleIds());
  }
}
