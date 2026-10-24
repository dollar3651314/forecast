package com.forecast.app.admin.ops;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 提现自动审批规则的修改记录：从审计日志 {@code withdrawal.auto_rule_update}（成功的）读，只读。只有 admin 模块使用。 */
@Mapper
interface AutoRuleAuditMapper {

  class Row {
    Long id;
    Long adminId;
    String adminName;
    String target;
    String reason;
    String beforeJson;
    String afterJson;
    LocalDateTime createdAt;
  }

  /** 最近 limit 次修改（新的在前） */
  List<Row> changes(@Param("limit") int limit);

  /** 保存出这些版本的修改（target 是 v + 版本号） */
  List<Row> versions(@Param("targets") List<String> targets);
}
