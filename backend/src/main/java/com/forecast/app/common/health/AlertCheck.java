package com.forecast.app.common.health;

import java.util.List;

/**
 * 业务告警检查（需求 v2 E8，功能取舍对照表 M-32、F-30、P-20）：行情全部断流、扫块停滞、链节点连不上、邮件连续发送失败。
 * <p>和 {@link HealthCheck} 的区别：{@link HealthCheck} 回答"这个应用活着吗"，不通过时健康检查返回 503，部署脚本会认为新版本没起来并自动回滚；
 * 告警检查回答"外部的东西出问题了吗"（LP、节点、邮件服务商），这些故障换版本也解决不了，**不能**让部署回滚，所以：
 * <ul>
 *   <li>健康检查 {@code GET /api/health} 仍然返回 200、{@code status} 仍然是 UP，另外在 {@code alerts} 里列出每一项（UP / DOWN），
 *       {@code alert} 是汇总（OK / ALERT）。这两个字段只给后台域名和服务器本机直连（客户端域名、官网不返回，见 HealthController#showAlerts）。</li>
 *   <li>告警发生和恢复时给告警邮箱发邮件（通知模块的 {@code AlertMonitor}：一次故障一封、恢复一封，一直没恢复每 24 小时提醒一次；后台"参数设置 → 告警邮件"可以关掉或静音某几项）。</li>
 * </ul>
 * <b>只检查配置了的东西</b>：没配置的链、没有报价源时不返回对应的项，测试环境不会误报。
 * 检查必须很快（只看内存里的状态，不访问网络和数据库）：健康检查和告警定时任务都会调用。
 * <p><b>"未开通"和"出错"分开</b>（Amos 2026-10-27："告警邮件要能控制是否发送"，开发记录 S12b）：功能打开了、但还没有配 key / 地址
 * （Amos 故意不配的可选功能）用 {@link Status#NOT_CONFIGURED}，<b>不用文字判断</b>。健康检查 {@code alerts} 里照样显示成 {@code DOWN}
 * （部署脚本、{@code fc} 命令按 DOWN 列出"没填的可选功能"），但<b>不发告警邮件</b>；只有"配置了但出错"（{@link Status#DOWN}）才发。
 * 现有各项：
 * <ul>
 *   <li>可能是"未开通"的：{@code telegram.bot}（没配 {@code FC_TELEGRAM_BOT_TOKEN}）、{@code source.football_data / balldontlie / thesportsdb}
 *       （打开了但没配 key 或地址）、{@code deposit.evm}（要扫以太坊 / BSC，但四个归集合约地址一个都没填；填了一部分或填错是 DOWN）。</li>
 *   <li>只有"正常 / 出错"的（没配置时根本不出现）：{@code scan.*}、{@code node.*}、{@code mail}、{@code source.binance / okx / coinbase}、
 *       {@code sweep.*}、{@code hot.*}、{@code board.binance}、{@code cards.font}。</li>
 * </ul>
 */
public interface AlertCheck {

  /** 一项告警的状态 */
  enum Status {
    /** 正常 */
    UP,
    /** 配置了但出错：发告警邮件 */
    DOWN,
    /** 未开通（没配 key / 地址的可选功能）：健康检查里照样是 DOWN，不发告警邮件 */
    NOT_CONFIGURED
  }

  /**
   * 一项告警的当前状态。
   *
   * @param name 健康检查 alerts 里的名字，例如 {@code market}、{@code scan.ERC20}、{@code node.TRC20}、{@code mail}
   * @param titleZh 告警邮件里的中文名，例如"行情全部断流"（后台"不发邮件的告警项"也显示它）
   * @param titleEn 英文名
   * @param status 正常 / 出错 / 未开通
   * @param detail 给运维看的说明（写日志和告警邮件，不出现在健康检查的返回里）
   */
  record Item(String name, String titleZh, String titleEn, Status status, String detail) {

    /** 只有"正常 / 出错"两种状态的项 */
    public Item(String name, String titleZh, String titleEn, boolean ok, String detail) {
      this(name, titleZh, titleEn, ok ? Status.UP : Status.DOWN, detail);
    }

    /** 未开通的项（健康检查里是 DOWN，不发告警邮件） */
    public static Item notConfigured(String name, String titleZh, String titleEn, String detail) {
      return new Item(name, titleZh, titleEn, Status.NOT_CONFIGURED, detail);
    }

    /** 正常 */
    public boolean ok() {
      return status == Status.UP;
    }

    /** 未开通 */
    public boolean notConfigured() {
      return status == Status.NOT_CONFIGURED;
    }
  }

  /** 现在的状态；没有配置、不需要检查时返回空列表 */
  List<Item> evaluate();
}
