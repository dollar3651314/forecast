package com.forecast.app.admin.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 第一个超级管理员（后台方案 G1）：系统里还没有任何管理员、并且 Amos 在服务器上填了 {@code FC_ADMIN_INIT_USERNAME}、{@code FC_ADMIN_INIT_PASSWORD} 时，
 * 启动时建一个超级管理员，首次登录必须改密码。已经有管理员时这两个变量不起作用。密码不进日志。
 */
@Component
class AdminBootstrap implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

  private final SecurityMapper mapper;
  private final AdminProperties props;

  AdminBootstrap(SecurityMapper mapper, AdminProperties props) {
    this.mapper = mapper;
    this.props = props;
  }

  @Override
  public void run(ApplicationArguments args) {
    create();
  }

  /** 建了返回 true */
  @Transactional
  boolean create() {
    if (mapper.countAdmins() > 0) return false;
    String user = props.initUsername() == null ? "" : props.initUsername().strip();
    if (user.isEmpty() || props.initPassword() == null || props.initPassword().isEmpty()) {
      log.warn("还没有任何后台管理员；在服务器上填 FC_ADMIN_INIT_USERNAME、FC_ADMIN_INIT_PASSWORD 后重启，会建第一个超级管理员");
      return false;
    }
    if (!user.matches("[A-Za-z0-9_.-]{3,40}")) {
      log.error("FC_ADMIN_INIT_USERNAME 只能用字母、数字和 _.-，3 到 40 位；没有建管理员");
      return false;
    }
    if (!Passwords.strong(props.initPassword())) {
      log.error("FC_ADMIN_INIT_PASSWORD 不够强（至少 10 位，含字母和数字）；没有建管理员");
      return false;
    }
    SecurityMapper.AdminRow a = new SecurityMapper.AdminRow();
    a.username = user;
    a.displayName = user;
    a.email = "";
    a.passwordHash = Passwords.hash(props.initPassword());
    a.mustChangePassword = true;
    mapper.insertAdmin(a);
    mapper.addRole(a.id, mapper.roleIdByCode("super_admin"));
    log.warn("已建第一个超级管理员 {}，首次登录必须改密码；可以从服务器上删掉 FC_ADMIN_INIT_PASSWORD", user);
    return true;
  }
}
