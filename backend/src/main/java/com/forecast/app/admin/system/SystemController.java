package com.forecast.app.admin.system;

import com.forecast.app.admin.security.AdminContext;
import com.forecast.app.admin.security.AdminLoginOnly;
import com.forecast.app.admin.security.Permission;
import com.forecast.app.admin.security.RequiresPermission;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后台"系统"菜单的接口（S16a）：当前管理员、管理员、角色、菜单、权限点、审计日志、系统配置。字段和原型 {@code web/admin/src/api/types.ts} 一致。
 * 每个接口都声明了权限点（{@link RequiresPermission}），没有声明的会被拦截器拒绝。
 */
@RestController
class SystemController {

  /** 当前管理员（原型 CurrentAdmin），另加可见菜单（只返回有权限、没有隐藏的，AC-32） */
  record Me(AdminUserService.AdminView admin, List<RoleService.RoleView> roles, List<String> permissions, boolean isSuperAdmin,
      List<SystemService.MenuNode> menus) {}

  record StatusBody(Boolean active, String reason) {}

  record ReasonBody(String reason) {}

  record MenuBody(Integer order, Boolean visible) {}

  record ConfigBody(String value, String reason) {}

  private final AdminUserService admins;
  private final RoleService roles;
  private final SystemService system;
  private final AuditQuery audits;

  SystemController(AdminUserService admins, RoleService roles, SystemService system, AuditQuery audits) {
    this.admins = admins;
    this.roles = roles;
    this.system = system;
    this.audits = audits;
  }

  @AdminLoginOnly
  @GetMapping("/api/admin/me")
  Me me() {
    AdminContext ctx = AdminContext.current();
    AdminUserService.AdminView a = admins.get(ctx.adminId());
    List<String> perms = java.util.Arrays.stream(Permission.values()).filter(ctx::has).map(p -> p.code).toList();
    return new Me(a, roles.of(a.roleIds().stream().map(Long::valueOf).toList()), perms, ctx.superAdmin(), system.visibleFor(ctx));
  }

  // ---------- 管理员 ----------

  @RequiresPermission(Permission.ADMIN_VIEW)
  @GetMapping("/api/admin/admins")
  Pages.Page<AdminUserService.AdminView> admins(@RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize,
      @RequestParam(required = false) String keyword) {
    return admins.list(keyword, page, pageSize);
  }

  /** 新建管理员：返回临时密码（只返回这一次），首次登录必须改 */
  @RequiresPermission(Permission.ADMIN_EDIT)
  @PostMapping("/api/admin/admins")
  AdminUserService.TemporaryPassword createAdmin(@RequestBody AdminUserService.AdminInput body) {
    return admins.create(AdminContext.current(), body);
  }

  @RequiresPermission(Permission.ADMIN_EDIT)
  @PutMapping("/api/admin/admins/{id}")
  AdminUserService.AdminView updateAdmin(@PathVariable long id, @RequestBody AdminUserService.AdminInput body) {
    admins.update(AdminContext.current(), id, body);
    return admins.get(id);
  }

  @RequiresPermission(Permission.ADMIN_EDIT)
  @PostMapping("/api/admin/admins/{id}/status")
  AdminUserService.AdminView setStatus(@PathVariable long id, @RequestBody StatusBody body) {
    boolean active = body != null && Boolean.TRUE.equals(body.active());
    admins.setStatus(AdminContext.current(), id, active, body == null ? null : body.reason());
    return admins.get(id);
  }

  /** 重置密码：返回新的临时密码（只返回这一次） */
  @RequiresPermission(Permission.ADMIN_EDIT)
  @PostMapping("/api/admin/admins/{id}/reset-password")
  AdminUserService.TemporaryPassword resetPassword(@PathVariable long id, @RequestBody ReasonBody body) {
    return admins.resetPassword(AdminContext.current(), id, body == null ? null : body.reason());
  }

  // ---------- 角色 ----------

  @RequiresPermission(Permission.ROLE_VIEW)
  @GetMapping("/api/admin/roles")
  List<RoleService.RoleView> roles() {
    return roles.list();
  }

  @RequiresPermission(Permission.ROLE_EDIT)
  @PostMapping("/api/admin/roles")
  RoleService.RoleView createRole(@RequestBody RoleService.RoleInput body) {
    return roles.create(AdminContext.current(), body);
  }

  @RequiresPermission(Permission.ROLE_EDIT)
  @PutMapping("/api/admin/roles/{id}")
  RoleService.RoleView updateRole(@PathVariable long id, @RequestBody RoleService.RoleInput body) {
    return roles.update(AdminContext.current(), id, body);
  }

  @RequiresPermission(Permission.ROLE_EDIT)
  @DeleteMapping("/api/admin/roles/{id}")
  void deleteRole(@PathVariable long id) {
    roles.delete(AdminContext.current(), id);
  }

  // ---------- 菜单、权限点 ----------

  @RequiresPermission(Permission.MENU_VIEW)
  @GetMapping("/api/admin/menus")
  List<SystemService.MenuNode> menus() {
    return system.menus();
  }

  @RequiresPermission(Permission.MENU_EDIT)
  @PutMapping("/api/admin/menus/{key}")
  List<SystemService.MenuNode> updateMenu(@PathVariable String key, @RequestBody MenuBody body) {
    system.updateMenu(key, body == null ? null : body.order(), body == null ? null : body.visible());
    return system.menus();
  }

  @RequiresPermission(Permission.PERMISSION_VIEW)
  @GetMapping("/api/admin/permissions")
  List<SystemService.PermissionView> permissions() {
    return system.permissions();
  }

  // ---------- 审计 ----------

  @RequiresPermission(Permission.AUDIT_VIEW)
  @GetMapping("/api/admin/audit-logs")
  Pages.Page<AuditQuery.AuditView> auditLogs(@RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize,
      @RequestParam(required = false) String action, @RequestParam(required = false) String admin, @RequestParam(defaultValue = "false") boolean highRiskOnly,
      @RequestParam(required = false) String keyword) {
    return audits.list(action, admin, highRiskOnly, keyword, page, pageSize);
  }

  @RequiresPermission(Permission.AUDIT_VIEW)
  @GetMapping("/api/admin/audit-logs/actions")
  List<String> auditActions() {
    return audits.actions();
  }

  // ---------- 系统配置 ----------

  @RequiresPermission(Permission.CONFIG_VIEW)
  @GetMapping("/api/admin/configs")
  List<SystemService.ConfigView> configs() {
    return system.configs();
  }

  @RequiresPermission(Permission.CONFIG_EDIT)
  @PutMapping("/api/admin/configs/{key}")
  SystemService.ConfigView updateConfig(@PathVariable String key, @RequestBody ConfigBody body) {
    return system.updateConfig(AdminContext.current(), key, body == null ? null : body.value(), body == null ? null : body.reason());
  }
}
