package com.forecast.app.common.config;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Optional;

/**
 * 后台可以改的系统配置（从复制源复制）。键名和单位、分组和后台 {@code web/admin} 的系统配置页一致。
 * 初始值在迁移脚本 V4__admin.sql 里（forecast 重建的基线），和 application.yml 的默认值一致。
 * forecast S1 删掉了调余额上限、追保、持仓上限。
 * <p>forecast S3：第一期参数（需求说明书第 6.2 节"参数设置"、第 3.3、3.7、3.8、3.10、3.11 节；初始值在迁移 V10）不在系统配置页，
 * 在"参数设置"（{@code /api/admin/params}）：品类默认费率归 {@link Params#FEE}（权限 fee:edit），其余归 {@link Params#BETTING}（权限 betting_params:edit），
 * 单笔最小提现 {@code withdraw.minAmount} 也从系统配置页搬到这里（S2 的遗留，需求第 2.2 节）。
 */
public enum ConfigKey {
  /** 单笔最小提现（RQ-16，默认 100）：S3 起在参数设置页改（betting_params:edit），必须大于每条链的提现手续费（admin.system.SystemService） */
  WITHDRAW_MIN_AMOUNT("withdraw.minAmount", Unit.USDT, "withdraw", BigDecimal.ZERO, new BigDecimal("1000000000"), 2, Params.BETTING),
  WITHDRAW_MAX_SINGLE("withdraw.maxSingle", Unit.USDT, "withdraw", BigDecimal.ONE),
  WITHDRAW_DAILY_LIMIT("withdraw.dailyLimit", Unit.USDT, "withdraw", BigDecimal.ONE),
  WITHDRAW_COOLING_HOURS("withdraw.coolingHours", Unit.HOURS, "withdraw", BigDecimal.ZERO),
  WITHDRAW_LARGE_THRESHOLD("withdraw.largeThreshold", Unit.USDT, "withdraw", BigDecimal.ONE),
  WITHDRAW_LARGE_DELAY_HOURS("withdraw.largeDelayHours", Unit.HOURS, "withdraw", BigDecimal.ZERO),
  WITHDRAW_FEE_ERC20("withdraw.fee.ERC20", Unit.USDT, "withdraw", BigDecimal.ZERO),
  WITHDRAW_FEE_TRC20("withdraw.fee.TRC20", Unit.USDT, "withdraw", BigDecimal.ZERO),
  WITHDRAW_FEE_BSC("withdraw.fee.BSC", Unit.USDT, "withdraw", BigDecimal.ZERO),
  WHITELIST_EFFECTIVE_HOURS("whitelist.effectiveHours", Unit.HOURS, "withdraw", BigDecimal.ZERO),
  WHITELIST_MAX_ADDRESSES("whitelist.maxAddresses", Unit.COUNT, "withdraw", BigDecimal.ONE),
  /** 充值确认数只能调高（后台方案 G7）：最小值就是现在的值 */
  DEPOSIT_CONFIRMATIONS_ERC20("deposit.confirmations.ERC20", Unit.COUNT, "deposit", BigDecimal.valueOf(12)),
  DEPOSIT_CONFIRMATIONS_TRC20("deposit.confirmations.TRC20", Unit.COUNT, "deposit", BigDecimal.valueOf(19)),
  DEPOSIT_CONFIRMATIONS_BSC("deposit.confirmations.BSC", Unit.COUNT, "deposit", BigDecimal.valueOf(15)),
  /** 后台管理员的登录锁定（后台方案 G2）；客户的登录锁定沿用现有系统，在 application.yml */
  LOGIN_LOCK_THRESHOLD("login.lockThreshold", Unit.COUNT, "security", BigDecimal.ONE),
  LOGIN_LOCK_MINUTES("login.lockMinutes", Unit.MINUTES, "security", BigDecimal.ONE),
  /** 后台 IP 白名单：逗号分隔的 IP 或网段，为空不限制（后台方案 G3） */
  ADMIN_IP_WHITELIST("admin.ipWhitelist", Unit.TEXT, "security", null),
  /**
   * 热钱包余额告警的下限（Amos 2026-10-17："告警的默认下限让我可以设置，现在设置成零"）：余额低于它时发 E8 告警邮件。
   * <b>0（默认，迁移 V25）= 不告警</b>：这一项不检查、告警项不出现；余额照常查询，后台对账页能看到。代码见 wallet/withdraw/HotBalanceMonitor
   */
  HOT_MIN_USDT_ERC20("hotWallet.minUsdt.ERC20", Unit.USDT, "hotWallet", BigDecimal.ZERO),
  HOT_MIN_USDT_BSC("hotWallet.minUsdt.BSC", Unit.USDT, "hotWallet", BigDecimal.ZERO),
  HOT_MIN_USDT_TRC20("hotWallet.minUsdt.TRC20", Unit.USDT, "hotWallet", BigDecimal.ZERO),
  HOT_MIN_ETH("hotWallet.minGas.ERC20", Unit.ETH, "hotWallet", BigDecimal.ZERO),
  HOT_MIN_BNB("hotWallet.minGas.BSC", Unit.BNB, "hotWallet", BigDecimal.ZERO),
  HOT_MIN_TRX("hotWallet.minGas.TRC20", Unit.TRX, "hotWallet", BigDecimal.ZERO),
  /**
   * 提现自动审批（v2，技术方案/v2-提现自动审批.md 第 7 节）：总开关（<b>默认 false</b>，迁移 V26）、13 条规则的开关和 10 个数值、配置版本号（R13 的两个键在迁移 V27）。
   * 在"出金审核 → 自动审批规则"页一次保存（权限 withdrawal:auto_rule_edit，高危），不在系统配置页；取值范围和 R1 ≤ R2 ≤ R3 的校验在 wallet.api.AutoApprove
   */
  AUTO_APPROVE_ENABLED("withdraw.autoApprove.enabled", Unit.BOOL, "autoApprove", null, false),
  AUTO_APPROVE_VERSION("withdraw.autoApprove.version", Unit.COUNT, "autoApprove", BigDecimal.ZERO, false),
  AUTO_APPROVE_R1_ENABLED("withdraw.autoApprove.R1.enabled", Unit.BOOL, "autoApprove", null, false),
  AUTO_APPROVE_R2_ENABLED("withdraw.autoApprove.R2.enabled", Unit.BOOL, "autoApprove", null, false),
  AUTO_APPROVE_R3_ENABLED("withdraw.autoApprove.R3.enabled", Unit.BOOL, "autoApprove", null, false),
  AUTO_APPROVE_R4_ENABLED("withdraw.autoApprove.R4.enabled", Unit.BOOL, "autoApprove", null, false),
  AUTO_APPROVE_R5_ENABLED("withdraw.autoApprove.R5.enabled", Unit.BOOL, "autoApprove", null, false),
  AUTO_APPROVE_R6_ENABLED("withdraw.autoApprove.R6.enabled", Unit.BOOL, "autoApprove", null, false),
  AUTO_APPROVE_R7_ENABLED("withdraw.autoApprove.R7.enabled", Unit.BOOL, "autoApprove", null, false),
  AUTO_APPROVE_R8_ENABLED("withdraw.autoApprove.R8.enabled", Unit.BOOL, "autoApprove", null, false),
  AUTO_APPROVE_R9_ENABLED("withdraw.autoApprove.R9.enabled", Unit.BOOL, "autoApprove", null, false),
  AUTO_APPROVE_R10_ENABLED("withdraw.autoApprove.R10.enabled", Unit.BOOL, "autoApprove", null, false),
  AUTO_APPROVE_R11_ENABLED("withdraw.autoApprove.R11.enabled", Unit.BOOL, "autoApprove", null, false),
  AUTO_APPROVE_R12_ENABLED("withdraw.autoApprove.R12.enabled", Unit.BOOL, "autoApprove", null, false),
  AUTO_APPROVE_R13_ENABLED("withdraw.autoApprove.R13.enabled", Unit.BOOL, "autoApprove", null, false),
  AUTO_APPROVE_R1_MAX_AMOUNT("withdraw.autoApprove.R1.maxAmount", Unit.USDT, "autoApprove", BigDecimal.ZERO, false),
  AUTO_APPROVE_R2_MAX_USER24H("withdraw.autoApprove.R2.maxUser24h", Unit.USDT, "autoApprove", BigDecimal.ZERO, false),
  AUTO_APPROVE_R3_MAX_PLATFORM24H("withdraw.autoApprove.R3.maxPlatform24h", Unit.USDT, "autoApprove", BigDecimal.ZERO, false),
  AUTO_APPROVE_R6_HOURS("withdraw.autoApprove.R6.hours", Unit.HOURS, "autoApprove", BigDecimal.ZERO, false),
  AUTO_APPROVE_R7_DAYS("withdraw.autoApprove.R7.days", Unit.COUNT, "autoApprove", BigDecimal.ZERO, false),
  AUTO_APPROVE_R8_MULTIPLE("withdraw.autoApprove.R8.multiple", Unit.MULTIPLE, "autoApprove", BigDecimal.ZERO, false),
  AUTO_APPROVE_R10_MAX_PROFIT7D("withdraw.autoApprove.R10.maxProfit7d", Unit.USDT, "autoApprove", BigDecimal.ZERO, false),
  AUTO_APPROVE_R10_MAX_PROFIT_PCT("withdraw.autoApprove.R10.maxProfitPct", Unit.PERCENT, "autoApprove", BigDecimal.ZERO, false),
  AUTO_APPROVE_R11_MAX_COUNT("withdraw.autoApprove.R11.maxCount", Unit.COUNT, "autoApprove", BigDecimal.ZERO, false),
  /** R13 盈利客户的门槛（累计净盈利 &gt; 它就转人工），默认 0，可以是 0 */
  AUTO_APPROVE_R13_MAX_NET_PROFIT("withdraw.autoApprove.R13.maxNetProfit", Unit.USDT, "autoApprove", BigDecimal.ZERO, false),
  /**
   * 资金归集（v2，技术方案/v2-半自动归集.md 第 4.5、4.9、4.14 节，迁移 V28）：门槛、手续费占比上限（Amos 10-20 SW-6：后台可改，默认 100 / 10 / 100 USDT、3%）、
   * 以太坊 / BSC 上线初期金额上限（每个地址 5,000、每批 20,000 USDT，0 = 不限；Amos 10-20 保留）、发起账户余额告警下限（0 = 不告警）、版本号。
   * 在"资金归集 → 归集设置"页一次保存（权限 sweep:config，高危），不在系统配置页；取值范围在 wallet.api.SweepSettings
   */
  SWEEP_VERSION("sweep.version", Unit.COUNT, "sweep", BigDecimal.ZERO, false),
  SWEEP_THRESHOLD_ERC20("sweep.threshold.ERC20", Unit.USDT, "sweep", BigDecimal.ZERO, false),
  SWEEP_THRESHOLD_BSC("sweep.threshold.BSC", Unit.USDT, "sweep", BigDecimal.ZERO, false),
  SWEEP_THRESHOLD_TRC20("sweep.threshold.TRC20", Unit.USDT, "sweep", BigDecimal.ZERO, false),
  SWEEP_MAX_FEE_RATIO_PCT("sweep.maxFeeRatioPct", Unit.PERCENT, "sweep", BigDecimal.ZERO, false),
  SWEEP_CAP_PER_ADDRESS("sweep.evmCap.perAddress", Unit.USDT, "sweep", BigDecimal.ZERO, false),
  SWEEP_CAP_PER_BATCH("sweep.evmCap.perBatch", Unit.USDT, "sweep", BigDecimal.ZERO, false),
  SWEEP_OPERATOR_MIN_ERC20("sweep.operatorMin.ERC20", Unit.ETH, "sweep", BigDecimal.ZERO, false),
  SWEEP_OPERATOR_MIN_BSC("sweep.operatorMin.BSC", Unit.BNB, "sweep", BigDecimal.ZERO, false),
  /*
   * ---------- forecast S3：第一期参数（参数设置页，迁移 V10 的初始值） ----------
   * 品类默认费率（需求第 3.8 节、RQ-4）：盈利部分的百分比，0～20，最多 2 位小数，初始都是 5。新建市场时带出，改了只影响之后新建的市场
   */
  FEE_FOOTBALL("market.fee.FOOTBALL", Unit.PERCENT, "fee", BigDecimal.ZERO, BigDecimal.valueOf(20), 2, Params.FEE),
  FEE_NBA("market.fee.NBA", Unit.PERCENT, "fee", BigDecimal.ZERO, BigDecimal.valueOf(20), 2, Params.FEE),
  FEE_CRYPTO_15M("market.fee.CRYPTO_15M", Unit.PERCENT, "fee", BigDecimal.ZERO, BigDecimal.valueOf(20), 2, Params.FEE),
  FEE_CRYPTO_1H("market.fee.CRYPTO_1H", Unit.PERCENT, "fee", BigDecimal.ZERO, BigDecimal.valueOf(20), 2, Params.FEE),
  FEE_MACRO("market.fee.MACRO", Unit.PERCENT, "fee", BigDecimal.ZERO, BigDecimal.valueOf(20), 2, Params.FEE),
  /** 下注限额（需求第 3.3 节）：单笔最小 1、最大 5,000 USDT，最多 2 位小数（S4 下注时用） */
  BET_MIN_AMOUNT("bet.minAmount", Unit.USDT, "bet", new BigDecimal("0.01"), new BigDecimal("1000000000"), 2, Params.BETTING),
  BET_MAX_AMOUNT("bet.maxAmount", Unit.USDT, "bet", new BigDecimal("0.01"), new BigDecimal("1000000000"), 2, Params.BETTING),
  /** 单客户单市场累计上限（按品类，默认 10,000；加密 15 分钟 / 1 小时 2,000）：新建市场时带出，单个市场只能调低 */
  USER_CAP_FOOTBALL("bet.userCap.FOOTBALL", Unit.USDT, "bet", new BigDecimal("0.01"), new BigDecimal("1000000000"), 2, Params.BETTING),
  USER_CAP_NBA("bet.userCap.NBA", Unit.USDT, "bet", new BigDecimal("0.01"), new BigDecimal("1000000000"), 2, Params.BETTING),
  USER_CAP_CRYPTO_15M("bet.userCap.CRYPTO_15M", Unit.USDT, "bet", new BigDecimal("0.01"), new BigDecimal("1000000000"), 2, Params.BETTING),
  USER_CAP_CRYPTO_1H("bet.userCap.CRYPTO_1H", Unit.USDT, "bet", new BigDecimal("0.01"), new BigDecimal("1000000000"), 2, Params.BETTING),
  USER_CAP_MACRO("bet.userCap.MACRO", Unit.USDT, "bet", new BigDecimal("0.01"), new BigDecimal("1000000000"), 2, Params.BETTING),
  /** 加密市场的截止提前量（窗口开始前 30 秒，RQ-13）和开放下注提前量（15 分钟 60 分钟、1 小时 180 分钟）；S6 自动生成时用 */
  CRYPTO_CLOSE_LEAD_SECONDS("crypto.closeLeadSeconds", Unit.SECONDS, "crypto", BigDecimal.ZERO, BigDecimal.valueOf(3600), 0, Params.BETTING),
  CRYPTO_OPEN_LEAD_15M("crypto.openLeadMinutes.CRYPTO_15M", Unit.MINUTES, "crypto", BigDecimal.ONE, BigDecimal.valueOf(10_080), 0, Params.BETTING),
  CRYPTO_OPEN_LEAD_1H("crypto.openLeadMinutes.CRYPTO_1H", Unit.MINUTES, "crypto", BigDecimal.ONE, BigDecimal.valueOf(10_080), 0, Params.BETTING),
  /** 争议窗口（平台裁决的市场，RQ-9）：体育 2 小时、时事 24 小时；新建市场时带出。加密是自动裁决，没有争议窗口 */
  DISPUTE_WINDOW_FOOTBALL("dispute.windowMinutes.FOOTBALL", Unit.MINUTES, "dispute", BigDecimal.ONE, BigDecimal.valueOf(10_080), 0, Params.BETTING),
  DISPUTE_WINDOW_NBA("dispute.windowMinutes.NBA", Unit.MINUTES, "dispute", BigDecimal.ONE, BigDecimal.valueOf(10_080), 0, Params.BETTING),
  DISPUTE_WINDOW_MACRO("dispute.windowMinutes.MACRO", Unit.MINUTES, "dispute", BigDecimal.ONE, BigDecimal.valueOf(10_080), 0, Params.BETTING),
  /*
   * forecast S7（迁移 V15）：加密市场自动裁决失败、转人工后由人确认的结果也公示，争议窗口按体育人工裁决的 2 小时（开发记录 S7 D-75）；
   * 市场建的时候是自动裁决，没有带出争议窗口，确认时按这里取（MarketCategory 不变：新建加密草稿仍不带出争议窗口）
   */
  DISPUTE_WINDOW_CRYPTO_15M("dispute.windowMinutes.CRYPTO_15M", Unit.MINUTES, "dispute", BigDecimal.ONE, BigDecimal.valueOf(10_080), 0, Params.BETTING),
  DISPUTE_WINDOW_CRYPTO_1H("dispute.windowMinutes.CRYPTO_1H", Unit.MINUTES, "dispute", BigDecimal.ONE, BigDecimal.valueOf(10_080), 0, Params.BETTING),
  /** 争议处理时限（需求第 4.4 节：提交后 24 小时内给出结论；超时裁决台标红、邮件提醒） */
  DISPUTE_HANDLE_MINUTES("dispute.handleMinutes", Unit.MINUTES, "dispute", BigDecimal.ONE, BigDecimal.valueOf(10_080), 0, Params.BETTING),
  /** 提议时限（需求第 4.4 节，按品类）：体育、加密转人工的进入裁决台后 2 小时；时事事件结束后 24 小时 */
  RESOLVE_PROPOSE_FOOTBALL("resolve.proposeMinutes.FOOTBALL", Unit.MINUTES, "resolve", BigDecimal.ONE, BigDecimal.valueOf(10_080), 0, Params.BETTING),
  RESOLVE_PROPOSE_NBA("resolve.proposeMinutes.NBA", Unit.MINUTES, "resolve", BigDecimal.ONE, BigDecimal.valueOf(10_080), 0, Params.BETTING),
  RESOLVE_PROPOSE_CRYPTO_15M("resolve.proposeMinutes.CRYPTO_15M", Unit.MINUTES, "resolve", BigDecimal.ONE, BigDecimal.valueOf(10_080), 0, Params.BETTING),
  RESOLVE_PROPOSE_CRYPTO_1H("resolve.proposeMinutes.CRYPTO_1H", Unit.MINUTES, "resolve", BigDecimal.ONE, BigDecimal.valueOf(10_080), 0, Params.BETTING),
  RESOLVE_PROPOSE_MACRO("resolve.proposeMinutes.MACRO", Unit.MINUTES, "resolve", BigDecimal.ONE, BigDecimal.valueOf(10_080), 0, Params.BETTING),
  /** 确认时限（需求第 4.4 节：提议后 2 小时） */
  RESOLVE_CONFIRM_MINUTES("resolve.confirmMinutes", Unit.MINUTES, "resolve", BigDecimal.ONE, BigDecimal.valueOf(10_080), 0, Params.BETTING),
  /**
   * forecast S8（迁移 V16）：体育市场从赛程生成草稿时带出的时间（需求第 3.7 节第 5 条）：开赛前几分钟截止（默认 5），
   * 事件结束 = 开赛 + 几分钟（足球 120、NBA 180；到了进入待裁决、开始看数据源是否完赛）
   */
  SPORTS_CLOSE_LEAD_FOOTBALL("sports.closeLeadMinutes.FOOTBALL", Unit.MINUTES, "sports", BigDecimal.ZERO, BigDecimal.valueOf(1440), 0, Params.BETTING),
  SPORTS_CLOSE_LEAD_NBA("sports.closeLeadMinutes.NBA", Unit.MINUTES, "sports", BigDecimal.ZERO, BigDecimal.valueOf(1440), 0, Params.BETTING),
  SPORTS_EVENT_MINUTES_FOOTBALL("sports.eventMinutes.FOOTBALL", Unit.MINUTES, "sports", BigDecimal.valueOf(60), BigDecimal.valueOf(1440), 0, Params.BETTING),
  SPORTS_EVENT_MINUTES_NBA("sports.eventMinutes.NBA", Unit.MINUTES, "sports", BigDecimal.valueOf(60), BigDecimal.valueOf(1440), 0, Params.BETTING),
  /** KYC 阈值（RQ-10）：累计充值超过它、没有通过证件 + 人脸认证的客户不能新下注（S10 起用） */
  KYC_DEPOSIT_THRESHOLD("kyc.depositThreshold", Unit.USDT, "kyc", BigDecimal.ZERO, new BigDecimal("1000000000"), 2, Params.BETTING);

  /**
   * 参数设置页的哪一组（决定改它要哪个权限点）；{@link #NONE} 不在参数设置页。
   */
  public enum Params {
    NONE,
    /** 品类默认费率：fee:edit */
    FEE,
    /** 下注限额、截止提前量、争议窗口、KYC 阈值、单笔最小提现：betting_params:edit */
    BETTING
  }

  /** 单位，和原型一致 */
  public enum Unit {
    USDT, HOURS, MINUTES, COUNT, TEXT, PERCENT,
    /** 热钱包手续费币（余额告警下限），最多 8 位小数 */
    ETH, BNB, TRX,
    /** 开关：true / false（提现自动审批） */
    BOOL,
    /** 倍数，最多 8 位小数（提现自动审批 R8） */
    MULTIPLE,
    /** 秒（加密市场的截止提前量，S3） */
    SECONDS;

    public String api() {
      return name().toLowerCase();
    }
  }

  public final String key;
  public final Unit unit;
  public final String group;
  /** 数值的最小值（含）；文本为空 */
  public final BigDecimal min;
  /**
   * 是否在系统配置页显示和修改。有专门页面和权限点的（提现自动审批、资金归集）不在系统配置页，
   * 否则有 config:edit 的人可以绕过专门的权限点去改
   */
  public final boolean configPage;
  /** 数值的最大值（含），为空时按单位的默认上限 */
  public final BigDecimal max;
  /** 最多几位小数，为空时按单位（金额 8 位，小时、分钟、个数 0 位） */
  public final Integer maxScale;
  /** 参数设置页的分组（S3），不在参数设置页的是 NONE */
  public final Params params;

  ConfigKey(String key, Unit unit, String group, BigDecimal min) {
    this(key, unit, group, min, true);
  }

  ConfigKey(String key, Unit unit, String group, BigDecimal min, boolean configPage) {
    this(key, unit, group, min, configPage, null, null, Params.NONE);
  }

  /** 参数设置页的项（不在系统配置页） */
  ConfigKey(String key, Unit unit, String group, BigDecimal min, BigDecimal max, int maxScale, Params params) {
    this(key, unit, group, min, false, max, maxScale, params);
  }

  ConfigKey(String key, Unit unit, String group, BigDecimal min, boolean configPage, BigDecimal max, Integer maxScale, Params params) {
    this.key = key;
    this.unit = unit;
    this.group = group;
    this.min = min;
    this.configPage = configPage;
    this.max = max;
    this.maxScale = maxScale;
    this.params = params;
  }

  public static Optional<ConfigKey> of(String key) {
    return Arrays.stream(values()).filter(k -> k.key.equals(key)).findFirst();
  }

  /** 校验并规范化新值；不合法返回空。金额最多 8 位小数，小时、分钟、个数是整数 */
  public Optional<String> normalize(String value) {
    if (value == null) return Optional.empty();
    String v = value.strip();
    if (unit == Unit.TEXT) return v.length() <= 1000 ? Optional.of(v) : Optional.empty();
    if (unit == Unit.BOOL) return "true".equals(v) || "false".equals(v) ? Optional.of(v) : Optional.empty();
    try {
      BigDecimal d = new BigDecimal(v);
      if (d.compareTo(min) < 0) return Optional.empty();
      if (max != null && d.compareTo(max) > 0) return Optional.empty();
      if (maxScale != null && d.stripTrailingZeros().scale() > maxScale) return Optional.empty();
      if (unit == Unit.USDT || unit == Unit.PERCENT || unit == Unit.ETH || unit == Unit.BNB || unit == Unit.TRX
          || unit == Unit.MULTIPLE) {
        if (d.scale() > 8 || d.compareTo(new BigDecimal("1000000000")) > 0) return Optional.empty();
        return Optional.of(d.stripTrailingZeros().toPlainString());
      }
      if (d.stripTrailingZeros().scale() > 0 || d.compareTo(BigDecimal.valueOf(100_000)) > 0) return Optional.empty();
      return Optional.of(String.valueOf(d.intValueExact()));
    } catch (NumberFormatException | ArithmeticException e) {
      return Optional.empty();
    }
  }
}
