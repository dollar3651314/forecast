package com.forecast.app.admin.security;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 管理员编号 → 显示名（列表里的审核人、操作人、处理人）。 */
@Component
public class AdminNames {

  private final SecurityMapper mapper;

  AdminNames(SecurityMapper mapper) {
    this.mapper = mapper;
  }

  @Transactional(readOnly = true)
  public Map<Long, String> of(Collection<Long> ids) {
    Map<Long, String> out = new HashMap<>();
    var list = ids.stream().filter(Objects::nonNull).distinct().toList();
    if (list.isEmpty()) return out;
    for (SecurityMapper.NameRow r : mapper.names(list)) out.put(r.id, r.displayName);
    return out;
  }

  /** 锁住管理员这一行，在调用方的事务里 */
  public void lock(long adminId) {
    mapper.lockAdmin(adminId);
  }
}
