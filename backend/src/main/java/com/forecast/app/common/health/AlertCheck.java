package com.forecast.app.common.health;

import java.util.List;

/**
 * 业务告警检查（需求 v2 E8，功能取舍对照表 M-32、F-30、P-20）：行情全部断流、扫块停滞、链节点连不上、邮件连续发送失败。
 * <p>和 {@link HealthCheck} 的区别：{@link HealthCheck} 回答"这个应用活着吗"，不通过时健康检查返回 503，部署脚本会认为新版本没起来并自动回滚；
 * 告警检查回答"外部的东西出问题了吗"（LP、节点、邮件服务商），这些故障换版本也解决不了，**不能**让部署回滚，所以：
 * <ul>
 *   <li>健康检查 {@code GET /api/health} 仍然返回 200、{@code status} 仍然是 UP，另外在 {@code alerts} 里列出每一项（UP / DOWN），
 *       {@code alert} 是汇总（OK / ALERT）。这两个字段只给后台域名和服务器本机直连（客户端域名、官网不返回，见 HealthController#showAlerts）。</li>
 *   <li>告警发生和恢复时给告警邮箱发邮件（通知模块的 {@code AlertMonitor}，同一项 30 分钟内不重复）。</li>
 * </ul>
 * <b>只检查配置了的东西</b>：没配置的链、没有报价源时不返回对应的项，测试环境不会误报。
 * 检查必须很快（只看内存里的状态，不访问网络和数据库）：健康检查和告警定时任务都会调用。
 */
public interface AlertCheck {

  /**
   * 一项告警的当前状态。
   *
   * @param name 健康检查 alerts 里的名字，例如 {@code market}、{@code scan.ERC20}、{@code node.TRC20}、{@code mail}
   * @param titleZh 告警邮件里的中文名，例如"行情全部断流"
   * @param titleEn 英文名
   * @param ok 正常为 true
   * @param detail 给运维看的说明（写日志和告警邮件，不出现在健康检查的返回里）
   */
  record Item(String name, String titleZh, String titleEn, boolean ok, String detail) {}

  /** 现在的状态；没有配置、不需要检查时返回空列表 */
  List<Item> evaluate();
}
