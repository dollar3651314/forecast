package com.forecast.app.market.api;

import com.forecast.app.common.error.ValidationException;
import java.util.List;
import java.util.Optional;

/**
 * 后台的加密市场自动生成（forecast S6，需求说明书第 4.2 节；MQ-1 节目化）。只有 admin 模块调用；权限点和审计在 admin 模块（{@code admin/ops/AutogenOps}）。
 * <ul>
 *   <li><b>自动生成模板</b>（{@code market_autogen}）：币种、周期（15 分钟 / 1 小时）、生成方式——{@code schedule}（按场次，节目化，默认）：
 *       {@code slots} 里每个 UTC 窗口开始时间每天一场；{@code rolling}（可切换）：保证未来至少 {@code rollingCount} 个窗口可下注或待开放。
 *       新建、修改、关闭要 {@code market:autogen}（高危，原因必填）；<b>修改只能在关闭时</b>；启用要 {@code market:publish}，按第 4.1 节清单检查一次（勾选 {@code checked}）。</li>
 *   <li><b>规则文本模板</b>（{@code market_template}）：题目、规则、来源、选项（三语，含变量）。修改要 {@code market:autogen}，有启用中的自动生成用它时不能改（先关闭）。</li>
 * </ul>
 * 按模板生成的市场<b>直接上架</b>（第 4.2 节），开放、截止提前量用参数设置里的值，费率用品类默认值。
 * 编号一律字符串，时间 ISO-8601 UTC。字段错误一次列出全部（C56）。
 */
public interface MarketAutogenAdminApi {

  /**
   * 新建、修改自动生成模板的输入。
   *
   * @param symbol BTCUSDT / ETHUSDT
   * @param period 15M / 1H
   * @param mode schedule / rolling
   * @param slots schedule：窗口开始时间（UTC，"HH:mm"），15M 要是 15 分钟的整数倍、1H 要是整点；同一时刻只能一项。
   *     S13 起可以限定星期几："HH:mm@1,6,7"（ISO 星期：1 周一 … 7 周日，按 UTC 日期；不带 @ 或七天全选 = 每天）
   * @param rollingCount rolling：未来至少几个窗口（1～96；为空时 15M 用 4、1H 用 3，需求第 4.2 节）
   * @param generateAheadMinutes schedule：开放下注前多少分钟生成（0～10080；为空用 1440，F-I2-1：AC-80 接下来 24 小时的场次都已生成）
   * @param templateNo 规则文本模板编号；为空用第一个加密涨跌模板
   * @param userCap 单客户单市场上限；为空用品类默认值，只能调低
   */
  record AutogenInput(String symbol, String period, String mode, List<String> slots, Integer rollingCount, Integer generateAheadMinutes, String templateNo,
      String userCap) {}

  /** 接下来会生成的一个窗口（检查时看的样子） */
  record Window(String windowStart, String windowEnd, String openAt, String closeAt, boolean generated, String marketNo) {}

  /**
   * 一个自动生成模板。
   *
   * @param checkedTemplateVersion 启用时检查的规则文本模板版本
   * @param upcoming 接下来的几个窗口（启用中：已生成的带市场编号）
   * @param sample 按下一个窗口渲染出来的题目、规则、来源、选项（第 4.1 节清单检查时看）
   * @param missing 渲染出来的市场缺哪些必填项（第 4.1 节，空表示齐全；不齐全不能启用）
   */
  record Autogen(String autogenNo, String symbol, String period, String category, String mode, List<String> slots, int rollingCount,
      int generateAheadMinutes, String templateNo, String userCap, boolean enabled, Long checkedBy, String checkedAt, Integer checkedTemplateVersion,
      int version, String createdAt, String updatedAt, List<Window> upcoming, Sample sample, List<ValidationException.Field> missing) {}

  /** 渲染出来的样子 */
  record Sample(I18nText title, I18nText rules, String sourcePrimary, String sourceBackup, List<SampleOption> options) {}

  record SampleOption(String outcome, I18nText label, I18nText winCondition) {}

  /** 规则文本模板的一个选项：outcome 是 UP / DOWN（加密涨跌） */
  record TemplateOption(String outcome, I18nText label, I18nText winCondition) {}

  /** 规则文本模板（文字里可以用变量，见 {@code market/autogen/AutogenRender}） */
  record Template(String templateNo, String kind, String name, I18nText title, I18nText rules, String sourcePrimary, String sourceBackup,
      List<TemplateOption> options, int version, List<String> usedBy, boolean usedByEnabled, List<String> variables, String updatedAt) {}

  record TemplateInput(String name, I18nText title, I18nText rules, String sourcePrimary, String sourceBackup, List<TemplateOption> options) {}

  List<Autogen> list();

  Optional<Autogen> detail(long autogenNo);

  /** 新建（关闭状态）。同一币种同一周期只能有一个（409 {@code AUTOGEN_EXISTS}） */
  Autogen create(AutogenInput in, long adminId);

  /** 修改：只能在关闭时（启用中 409 {@code AUTOGEN_ENABLED}）；改了以后要重新检查才能启用 */
  Autogen update(long autogenNo, AutogenInput in, long adminId);

  /**
   * 启用（market:publish）：必须勾选"已按第 4.1 节清单检查"（{@code checked}），渲染出来的市场必填项齐全；记下检查人、时间、模板版本。
   * 启用以后由生成任务（每分钟）按配置生成并直接上架。
   */
  Autogen enable(long autogenNo, boolean checked, long adminId);

  /** 关闭：不再生成新的，已生成的照常（第 4.2 节） */
  Autogen disable(long autogenNo, long adminId);

  List<Template> templates();

  Optional<Template> template(long templateNo);

  /** 修改规则文本模板：有启用中的自动生成用它时 409 {@code TEMPLATE_IN_USE}（先关闭，改完重新检查启用） */
  Template updateTemplate(long templateNo, TemplateInput in, long adminId);
}
