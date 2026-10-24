package com.forecast.app.wallet.api;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 后台查看提现自动审批的判定记录和当前配置（技术方案/v2-提现自动审批.md 第 7.2 节）。只读；保存配置由后台模块写系统配置（{@link AutoApprove} 校验）。
 */
public interface AutoApproveAdminApi {

  /**
   * 一次判定的摘要。
   *
   * @param phase PRECHECK 提交时预判 / FINAL 冷静期结束时复判
   * @param outcome AUTO_APPROVED / MANUAL / MASTER_OFF / ERROR
   * @param failed 没通过和无法判定的规则，按编号排序
   */
  record Summary(long checkId, String phase, String outcome, List<String> failed, LocalDateTime decidedAt) {}

  /**
   * 一条规则的判定结果（快照）。
   *
   * @param status passed / failed / disabled / unknown
   * @param values 判定时的数值和阈值（字符串），后台按规则的模板显示
   */
  record RuleResult(String code, String status, Map<String, String> values) {}

  /**
   * 完整的判定记录：最近一次判定（冷静期中是预判，之后是复判）和逐条结果（判定时有几条就是几条：R13 上线前的记录只有 12 条）；复判时附带提交时的预判摘要。
   */
  record Detail(Summary summary, boolean masterEnabled, long configVersion, List<RuleResult> rules, Summary precheck) {}

  /** 当前配置（总开关、13 条规则、版本号） */
  AutoApprove.Settings settings();

  /** 这些提现最近一次判定的摘要；没有判定记录的不在结果里 */
  Map<Long, Summary> latest(Collection<Long> withdrawalIds);

  /** 一笔提现的完整判定记录；没有判定记录（功能上线前的提现）时为空 */
  Optional<Detail> detail(long withdrawalId);
}
