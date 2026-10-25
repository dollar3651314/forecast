package com.forecast.app.admin.system;

import com.forecast.app.admin.security.AdminContext;
import com.forecast.app.admin.security.Audit;
import com.forecast.app.admin.security.Permission;
import com.forecast.app.common.error.BizException;
import com.forecast.app.common.error.ValidationException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 角色（C-11）：可以新建、改名称和权限点、删除（有成员的不能删）。超级管理员角色是内置的：不能改、不能删，自动拥有全部权限点。
 * 非超级管理员不能把自己没有的权限点放进角色，也不能修改含有自己没有的权限点的角色（防止给自己或别人提权）。
 */
@Service
class RoleService {

  static final String SUPER = "super_admin";
  private static final Pattern CODE = Pattern.compile("[a-z][a-z0-9_]{1,39}");

  /** 字段和原型 Role 一致；超级管理员的权限点列出全部 */
  record RoleView(String id, String code, String nameZh, String nameEn, String description, List<String> permissions, boolean builtIn, long memberCount) {}

  record RoleInput(String code, String nameZh, String nameEn, String description, List<String> permissions) {}

  private final SystemMapper mapper;
  private final Audit audit;

  RoleService(SystemMapper mapper, Audit audit) {
    this.mapper = mapper;
    this.audit = audit;
  }

  boolean includesSuper(Collection<Long> roleIds) {
    SystemMapper.RoleRow s = mapper.roleByCode(SUPER);
    return s != null && roleIds.contains(s.id);
  }

  Set<Permission> permissionsOf(long roleId) {
    SystemMapper.RoleRow r = mapper.role(roleId);
    if (r != null && SUPER.equals(r.code)) return EnumSet.allOf(Permission.class);
    Set<Permission> out = EnumSet.noneOf(Permission.class);
    for (String c : mapper.rolePermissions(roleId)) Permission.of(c).ifPresent(out::add);
    return out;
  }

  RoleView view(SystemMapper.RoleRow r) {
    List<String> perms = permissionsOf(r.id).stream().map(p -> p.code).toList();
    return new RoleView(String.valueOf(r.id), r.code, r.nameZh, r.nameEn, r.description, perms, r.builtIn, mapper.memberCount(r.id));
  }

  @Transactional(readOnly = true)
  List<RoleView> list() {
    return mapper.roles().stream().map(this::view).toList();
  }

  @Transactional(readOnly = true)
  List<RoleView> of(Collection<Long> ids) {
    return mapper.roles().stream().filter(r -> ids.contains(r.id)).map(this::view).toList();
  }

  @Transactional
  RoleView create(AdminContext actor, RoleInput in) {
    return audit.run("role.create", in == null ? "" : String.valueOf(in.code()), true, null, () -> {
      Set<Permission> perms = validate(in, true);
      if (SUPER.equals(in.code().strip()) || mapper.roleByCode(in.code().strip()) != null) throw new BizException("ROLE_CODE_TAKEN", "角色编码已存在");
      guard(actor, perms);
      SystemMapper.RoleRow r = new SystemMapper.RoleRow();
      r.code = in.code().strip();
      fill(r, in);
      mapper.insertRole(r);
      for (Permission p : perms) mapper.addRolePermission(r.id, p.code);
      return Audit.Change.of(view(mapper.role(r.id)), null, Map.of("code", r.code, "nameZh", r.nameZh, "permissions", codes(perms)));
    });
  }

  @Transactional
  RoleView update(AdminContext actor, long id, RoleInput in) {
    return audit.run("role.update", String.valueOf(id), true, null, () -> {
      SystemMapper.RoleRow r = mapper.role(id);
      if (r == null) throw new BizException("NOT_FOUND", "角色不存在");
      if (r.builtIn) throw new BizException("BUILT_IN_ROLE", "内置角色不能修改");
      Set<Permission> perms = validate(in, false);
      Set<Permission> old = permissionsOf(id);
      guard(actor, old);
      guard(actor, perms);
      String oldName = r.nameZh;
      fill(r, in);
      mapper.updateRole(r);
      mapper.clearRolePermissions(id);
      for (Permission p : perms) mapper.addRolePermission(id, p.code);
      return Audit.Change.of(view(mapper.role(id)), Map.of("nameZh", oldName, "permissions", codes(old)), Map.of("nameZh", r.nameZh, "permissions", codes(perms)));
    });
  }

  @Transactional
  void delete(AdminContext actor, long id) {
    audit.run("role.delete", String.valueOf(id), true, null, () -> {
      SystemMapper.RoleRow r = mapper.role(id);
      if (r == null) throw new BizException("NOT_FOUND", "角色不存在");
      if (r.builtIn) throw new BizException("BUILT_IN_ROLE", "内置角色不能删除");
      if (mapper.memberCount(id) > 0) throw new BizException("ROLE_IN_USE", "角色还有成员，不能删除");
      Set<Permission> old = permissionsOf(id);
      guard(actor, old);
      mapper.clearRolePermissions(id);
      mapper.deleteRole(id);
      return Audit.Change.of(null, Map.of("code", r.code, "permissions", codes(old)), null);
    });
  }

  private static void guard(AdminContext actor, Set<Permission> perms) {
    if (actor.superAdmin()) return;
    for (Permission p : perms) if (!actor.has(p)) throw new BizException("PERMISSION_EXCEEDS_OWN", "不能操作超出自己权限的角色");
  }

  private static void fill(SystemMapper.RoleRow r, RoleInput in) {
    r.nameZh = in.nameZh().strip();
    r.nameEn = in.nameEn().strip();
    r.description = in.description() == null ? "" : in.description().strip();
  }

  private static List<String> codes(Set<Permission> s) {
    return s.stream().map(p -> p.code).sorted().toList();
  }

  private static Set<Permission> validate(RoleInput in, boolean creating) {
    List<ValidationException.Field> errors = new ArrayList<>();
    if (in == null) throw new ValidationException(List.of(new ValidationException.Field("nameZh", "required")));
    if (creating && (in.code() == null || !CODE.matcher(in.code().strip()).matches())) errors.add(new ValidationException.Field("code", "invalid"));
    if (in.nameZh() == null || in.nameZh().isBlank() || in.nameZh().length() > 60) errors.add(new ValidationException.Field("nameZh", "required"));
    if (in.nameEn() == null || in.nameEn().isBlank() || in.nameEn().length() > 60) errors.add(new ValidationException.Field("nameEn", "required"));
    if (in.description() != null && in.description().length() > 200) errors.add(new ValidationException.Field("description", "tooLong"));
    Set<Permission> perms = EnumSet.noneOf(Permission.class);
    if (in.permissions() != null) {
      for (String c : in.permissions()) {
        var p = Permission.of(c);
        if (p.isEmpty()) {
          errors.add(new ValidationException.Field("permissions", "invalid"));
          break;
        }
        perms.add(p.get());
      }
    }
    if (!errors.isEmpty()) throw new ValidationException(errors);
    return perms;
  }
}
