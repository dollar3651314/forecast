package com.forecast.app.admin.system;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 审计日志查询（只读）。写在 {@code admin.security.Audit}。 */
@Mapper
interface AuditMapper {

  class Row {
    Long id;
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

  List<Row> audits(@Param("action") String action, @Param("admin") String admin, @Param("highRiskOnly") boolean highRiskOnly, @Param("keyword") String keyword,
      @Param("offset") int offset, @Param("limit") int limit);

  long countAudits(@Param("action") String action, @Param("admin") String admin, @Param("highRiskOnly") boolean highRiskOnly, @Param("keyword") String keyword);

  List<String> auditActions();
}
