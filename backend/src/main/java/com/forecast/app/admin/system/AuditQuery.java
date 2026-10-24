package com.forecast.app.admin.system;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 审计日志查询（只读）。 */
@Service
class AuditQuery {

  /** 字段和原型 AuditLog 一致 */
  record AuditView(String id, String time, String adminName, String ip, String action, String target, boolean highRisk, String result, String reason,
      Map<String, Object> before, Map<String, Object> after) {}

  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

  private final AuditMapper mapper;
  private final ObjectMapper json;

  AuditQuery(AuditMapper mapper, ObjectMapper json) {
    this.mapper = mapper;
    this.json = json;
  }

  @Transactional(readOnly = true)
  Pages.Page<AuditView> list(String action, String admin, boolean highRiskOnly, String keyword, int page, int pageSize) {
    Pages.Slice s = Pages.slice(page, pageSize);
    List<AuditView> items = mapper.audits(blank(action), blank(admin), highRiskOnly, blank(keyword), s.offset(), s.size()).stream()
        .map(r -> new AuditView(String.valueOf(r.id), AdminUserService.time(r.createdAt), r.adminName, r.ip, r.action, r.target, r.highRisk,
            r.result.toLowerCase(), r.reason, parse(r.beforeJson), parse(r.afterJson)))
        .toList();
    return new Pages.Page<>(items, mapper.countAudits(blank(action), blank(admin), highRiskOnly, blank(keyword)), s.page(), s.size());
  }

  List<String> actions() {
    return mapper.auditActions();
  }

  private Map<String, Object> parse(String s) {
    return s == null ? null : json.readValue(s, MAP);
  }

  private static String blank(String s) {
    return s == null || s.isBlank() ? null : s.strip();
  }
}
