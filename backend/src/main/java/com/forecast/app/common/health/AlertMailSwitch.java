package com.forecast.app.common.health;

import java.util.Set;

/**
 * 告警邮件发不发（forecast S12b）：后台"参数设置 → 告警邮件"的总开关和不发邮件的告警项，实现是 {@code common.config.AlertMailConfig}。
 * 只管通知模块的告警邮件（{@code AlertMonitor}）；健康检查的 {@code alerts} 不看它。
 */
public interface AlertMailSwitch {

  /** 总开关：false 时只写日志、不发邮件 */
  boolean mailEnabled();

  /** 不发邮件的告警项（健康检查 alerts 里的名字） */
  Set<String> muted();

  /** 都发（测试用） */
  AlertMailSwitch ALL = new AlertMailSwitch() {
    @Override
    public boolean mailEnabled() {
      return true;
    }

    @Override
    public Set<String> muted() {
      return Set.of();
    }
  };
}
