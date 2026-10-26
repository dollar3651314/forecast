package com.forecast.app.admin.api;

import java.util.Collection;
import java.util.List;

/**
 * 按权限点找管理员的邮箱（forecast S9，SQ-14：员工提醒按角色发）。notification 模块发员工邮件时用。
 * 超级管理员自动拥有全部权限点；只算状态为 ACTIVE、邮箱不为空的管理员。
 */
public interface StaffDirectoryApi {

  /**
   * @param permissionCodes 权限点编号（例如 {@code resolve:confirm}），有其中任意一个就算；不认识的编号忽略
   * @return 去重后的邮箱（按管理员 id 排序）；没有这样的管理员时为空
   */
  List<String> emailsWithAny(Collection<String> permissionCodes);
}
