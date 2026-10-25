package com.forecast.app.admin.security;

import com.forecast.app.admin.api.StaffDirectoryApi;
import java.util.Collection;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link StaffDirectoryApi} 的实现：角色权限点 + 超级管理员（自动拥有全部）。 */
@Service
class StaffDirectory implements StaffDirectoryApi {

  private final SecurityMapper mapper;

  StaffDirectory(SecurityMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  @Transactional(readOnly = true)
  public List<String> emailsWithAny(Collection<String> permissionCodes) {
    List<String> codes = permissionCodes == null ? List.of()
        : permissionCodes.stream().filter(c -> c != null && Permission.of(c.strip()).isPresent()).map(String::strip).distinct().toList();
    if (codes.isEmpty()) return List.of();
    return mapper.staffEmails(codes);
  }
}
