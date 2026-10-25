package com.forecast.app.wallet.api;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 提现自动审批的规则定义（技术方案/v2-提现自动审批.md 第 2、7 节）：13 条规则（{@link Rule}）的编号、数值参数、单位、默认值、取值范围，配置的读取和保存前的校验。
 * 和后台前端 {@code web/admin/src/config/autoApprove.ts} 一致。配置存在系统配置表（{@code common_config}，键都以 {@code withdraw.autoApprove.} 开头）。
 * 规则只决定"要不要人工看"，不放宽任何限额。
 */
public final class AutoApprove {

  public static final String PREFIX = "withdraw.autoApprove.";
  /** 总开关（默认 false = 全部人工审核） */
  public static final String MASTER_KEY = PREFIX + "enabled";
  /** 配置版本：每次保存 +1，判定记录里记这个号 */
  public static final String VERSION_KEY = PREFIX + "version";

  private AutoApprove() {}

  public enum Unit {
    USDT, HOURS, DAYS, TIMES, MULTIPLE, PERCENT
  }

  /**
   * 一个数值参数。
   *
   * @param decimals 最多几位小数；0 表示整数
   */
  public record Param(String key, Unit unit, String defaultValue, BigDecimal min, BigDecimal max, int decimals) {

    /** 校验并规范化（去掉末尾的 0）；不合法返回空 */
    public Optional<String> normalize(String raw) {
      if (raw == null) return Optional.empty();
      String v = raw.strip();
      if (!v.matches(decimals == 0 ? "\\d{1,12}" : "\\d{1,12}(\\.\\d{1," + decimals + "})?")) return Optional.empty();
      BigDecimal d = new BigDecimal(v);
      if (d.compareTo(min) < 0 || d.compareTo(max) > 0) return Optional.empty();
      return Optional.of(plain(d));
    }
  }

  private static Param usdt(String key, String def, String max) {
    return new Param(key, Unit.USDT, def, new BigDecimal("0.01"), new BigDecimal(max), 2);
  }

  private static Param integer(String key, Unit unit, String def, int min, int max) {
    return new Param(key, unit, def, BigDecimal.valueOf(min), BigDecimal.valueOf(max), 0);
  }

  /**
   * 13 条规则（从复制源复制，默认值沿用 复制源的规则表）。forecast S1 只删掉了 R12 里差价合约的两项检查；
   * R8～R10、R13 的口径（交易量、盈亏）在 S1 过渡期恒为 0（见 ledger.api.CustomerFlowApi），S10 按需求说明书第 8.2 节改为下注口径、新增 R14、R15。
   */
  public enum Rule {
    /** 单笔金额 ≤ 上限 */
    R1(usdt("maxAmount", "500", "1000000")),
    /** 该客户 24 小时内自动通过的累计（含本笔）≤ 上限，不能小于 R1 */
    R2(usdt("maxUser24h", "2000", "1000000")),
    /** 全平台 24 小时内自动通过的累计（含本笔）≤ 上限，不能小于 R2 */
    R3(usdt("maxPlatform24h", "20000", "100000000")),
    /** 不是该客户第一笔提现 */
    R4,
    /** 该地址以前成功提现过 */
    R5,
    /** 最近 N 小时内没有改密码、重置密码、改邮箱、冻结 / 解冻 */
    R6(integer("hours", Unit.HOURS, "48", 1, 720)),
    /** 注册满 N 天且 KYC 已通过 */
    R7(integer("days", Unit.DAYS, "7", 0, 365)),
    /** 充值后的交易量 ≥ 充值额 × N（S1 过渡：交易量恒为 0，S10 改为有效下注本金） */
    R8(new Param("multiple", Unit.MULTIPLE, "1", BigDecimal.ZERO, BigDecimal.valueOf(100), 2)),
    /** 累计提现（含本笔）≤ 累计充值 + 已实现盈亏 */
    R9,
    /** 近 7 天已实现盈利 ≤ 金额上限，或 ≤ 本金的 N% */
    R10(usdt("maxProfit7d", "5000", "1000000"), integer("maxProfitPct", Unit.PERCENT, "50", 1, 1000)),
    /** 当天提现次数（含本笔）≤ N */
    R11(integer("maxCount", Unit.TIMES, "3", 1, 50)),
    /** 系统状态正常：热钱包余额够（S10 加"彩池对账没有未处理差异"） */
    R12,
    /**
     * 盈利客户转人工：累计净盈利 ≤ 门槛才通过，等于门槛算通过。门槛默认 0，可以是 0。
     * S1 过渡：累计净盈利恒为 0；S10 改为 Σ(派彩 − 本金) + 补偿（需求说明书第 8.2 节）
     */
    R13(new Param("maxNetProfit", Unit.USDT, "0", BigDecimal.ZERO, BigDecimal.valueOf(1_000_000), 2));

    public final List<Param> params;

    Rule(Param... params) {
      this.params = List.of(params);
    }

    public String enabledKey() {
      return PREFIX + name() + ".enabled";
    }

    public String paramKey(Param p) {
      return PREFIX + name() + "." + p.key();
    }

    public Optional<Param> param(String key) {
      return params.stream().filter(p -> p.key().equals(key)).findFirst();
    }

    public static Optional<Rule> of(String code) {
      return Arrays.stream(values()).filter(r -> r.name().equals(code)).findFirst();
    }
  }

  /** 一条规则的设置；params 是数值参数（字符串，和系统配置一样），没有数值的规则为空 */
  public record RuleSetting(String code, boolean enabled, Map<String, String> params) {}

  /** 整份配置；version 从 0 开始，每次保存 +1 */
  public record Settings(boolean masterEnabled, List<RuleSetting> rules, long version) {

    public RuleSetting rule(Rule r) {
      return rules.stream().filter(x -> x.code().equals(r.name())).findFirst().orElseThrow();
    }

    /** 规则的数值参数；配置值不合法（例如有人直接改了数据库）时为空，这条规则按"无法判定"处理 */
    public Optional<BigDecimal> param(Rule r, String key) {
      Param p = r.param(key).orElseThrow();
      String raw = rule(r).params().get(key);
      return p.normalize(raw).map(BigDecimal::new);
    }

    /** 保存到系统配置的键和值 */
    public Map<String, String> toConfig() {
      Map<String, String> out = new LinkedHashMap<>();
      out.put(MASTER_KEY, String.valueOf(masterEnabled));
      for (Rule r : Rule.values()) {
        RuleSetting s = rule(r);
        out.put(r.enabledKey(), String.valueOf(s.enabled()));
        for (Param p : r.params) out.put(r.paramKey(p), s.params().get(p.key()));
      }
      return out;
    }
  }

  /**
   * 从系统配置读出整份配置（同一次读到的值互相一致）。没有的键用默认值；总开关的值不是 true 一律算关闭；
   * 规则开关的值不是 false 一律算开（宁可多判定）；数值原样保留，读的时候再校验。
   */
  public static Settings parse(Map<String, String> config) {
    boolean master = "true".equals(strip(config.get(MASTER_KEY)));
    List<RuleSetting> rules = new ArrayList<>();
    for (Rule r : Rule.values()) {
      boolean enabled = !"false".equals(strip(config.get(r.enabledKey())));
      Map<String, String> params = new LinkedHashMap<>();
      for (Param p : r.params) {
        String v = strip(config.get(r.paramKey(p)));
        params.put(p.key(), v == null || v.isEmpty() ? p.defaultValue() : v);
      }
      rules.add(new RuleSetting(r.name(), enabled, params));
    }
    long version;
    try {
      String v = strip(config.get(VERSION_KEY));
      version = v == null || v.isEmpty() ? 0 : Long.parseLong(v);
    } catch (NumberFormatException e) {
      version = -1;
    }
    return new Settings(master, List.copyOf(rules), version);
  }

  /** 一个字段错误：field 例如 {@code rules.R2.maxUser24h}，code 是 invalid / missing / ltR1 / ltR2 */
  public record Problem(String field, String code) {}

  /**
   * 保存前校验并规范化：每条规则都要有、每个数值在范围内且小数位不超、R1 ≤ R2 ≤ R3（否则后面的规则没有意义）。
   *
   * @return 规范化后的配置（version 沿用 0，由调用方决定）；有问题时 problems 不为空
   */
  public static Settings normalize(boolean master, List<RuleSetting> input, List<Problem> problems) {
    List<RuleSetting> rules = new ArrayList<>();
    for (Rule r : Rule.values()) {
      RuleSetting in = input == null ? null : input.stream().filter(x -> x != null && r.name().equals(x.code())).findFirst().orElse(null);
      if (in == null) {
        problems.add(new Problem("rules." + r.name(), "missing"));
        continue;
      }
      Map<String, String> params = new LinkedHashMap<>();
      for (Param p : r.params) {
        String raw = in.params() == null ? null : in.params().get(p.key());
        Optional<String> v = p.normalize(raw);
        if (v.isEmpty()) problems.add(new Problem("rules." + r.name() + "." + p.key(), "invalid"));
        params.put(p.key(), v.orElse(raw));
      }
      rules.add(new RuleSetting(r.name(), in.enabled(), params));
    }
    if (!problems.isEmpty()) return null;
    Settings s = new Settings(master, List.copyOf(rules), 0);
    BigDecimal r1 = s.param(Rule.R1, "maxAmount").orElseThrow();
    BigDecimal r2 = s.param(Rule.R2, "maxUser24h").orElseThrow();
    BigDecimal r3 = s.param(Rule.R3, "maxPlatform24h").orElseThrow();
    if (r2.compareTo(r1) < 0) problems.add(new Problem("rules.R2.maxUser24h", "ltR1"));
    if (r3.compareTo(r2) < 0) problems.add(new Problem("rules.R3.maxPlatform24h", "ltR2"));
    return problems.isEmpty() ? s : null;
  }

  /** 金额、倍数的显示和保存格式：去掉末尾的 0，0 写成 "0" */
  public static String plain(BigDecimal v) {
    if (v == null) return null;
    return v.signum() == 0 ? "0" : v.stripTrailingZeros().toPlainString();
  }

  private static String strip(String s) {
    return s == null ? null : s.strip();
  }
}
