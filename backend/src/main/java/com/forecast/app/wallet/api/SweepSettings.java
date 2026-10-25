package com.forecast.app.wallet.api;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 资金归集设置（v2，技术方案/v2-半自动归集.md 第 4.5、4.9、4.14 节）：存在系统配置 {@code sweep.*}（迁移 V28），只能在"资金归集 → 归集设置"页改
 * （{@code sweep:config}，高危、原因必填、审计、带版本号）。前端 {@code web/admin/src/config/sweep.ts} 的 {@code SWEEP_DEFAULTS} 是同一组默认值。
 *
 * @param thresholds 门槛（USDT）：余额达到它才归集；键 ERC20 / BSC / TRC20（TRC20 下一阶段用）
 * @param maxFeeRatioPct 手续费占比上限（%）：预估手续费折美元 &gt; 余额 × 它 时这次跳过
 * @param capPerAddress 以太坊、BSC 上线初期每个地址每次的上限（USDT，0 = 不限）：超过的地址这次跳过，由 Safe 手动处理
 * @param capPerBatch 以太坊、BSC 上线初期每批的上限（USDT，0 = 不限）
 * @param operatorMin 发起账户手续费币余额告警下限（ETH / BNB，0 = 不告警）；键 ERC20 / BSC
 * @param version 配置版本，每次保存 +1
 */
public record SweepSettings(Map<String, BigDecimal> thresholds, BigDecimal maxFeeRatioPct, BigDecimal capPerAddress, BigDecimal capPerBatch,
    Map<String, BigDecimal> operatorMin, long version) {

  public static final String PREFIX = "sweep.";
  public static final String VERSION_KEY = "sweep.version";
  public static final List<String> CHAINS = List.of("ERC20", "BSC", "TRC20");
  public static final List<String> EVM_CHAINS = List.of("ERC20", "BSC");

  /** 默认值（Amos 10-20 确认的门槛和占比；上线初期上限 5,000 / 20,000，Amos 10-20 保留） */
  public static final SweepSettings DEFAULTS = new SweepSettings(
      ordered(Map.of("ERC20", new BigDecimal("100"), "BSC", new BigDecimal("10"), "TRC20", new BigDecimal("100")), CHAINS), new BigDecimal("3"),
      new BigDecimal("5000"), new BigDecimal("20000"), ordered(Map.of("ERC20", BigDecimal.ZERO, "BSC", BigDecimal.ZERO), EVM_CHAINS), 0);

  /** 字段错误：field 用前端的名字（thresholds.ERC20、maxFeeRatioPct、evmCaps.perAddress、operatorMin.BSC） */
  public record Problem(String field, String code) {}

  /** 保存时的输入（字符串，原样来自页面） */
  public record Input(Map<String, String> thresholds, String maxFeeRatioPct, String capPerAddress, String capPerBatch, Map<String, String> operatorMin) {}

  public BigDecimal threshold(String chain) {
    return thresholds.getOrDefault(chain, DEFAULTS.thresholds.get(chain));
  }

  /** 从系统配置的快照读；缺的或不合法的用默认值 */
  public static SweepSettings parse(Map<String, String> config) {
    Map<String, BigDecimal> th = new LinkedHashMap<>();
    for (String c : CHAINS) th.put(c, num(config.get("sweep.threshold." + c), DEFAULTS.thresholds.get(c)));
    Map<String, BigDecimal> om = new LinkedHashMap<>();
    for (String c : EVM_CHAINS) om.put(c, num(config.get("sweep.operatorMin." + c), BigDecimal.ZERO));
    long v;
    try {
      v = Long.parseLong(config.getOrDefault(VERSION_KEY, "0").strip());
    } catch (NumberFormatException e) {
      v = 0;
    }
    return new SweepSettings(th, num(config.get("sweep.maxFeeRatioPct"), DEFAULTS.maxFeeRatioPct), num(config.get("sweep.evmCap.perAddress"), DEFAULTS.capPerAddress),
        num(config.get("sweep.evmCap.perBatch"), DEFAULTS.capPerBatch), om, v);
  }

  /** 要写进系统配置的键值（不含版本号） */
  public Map<String, String> toConfig() {
    Map<String, String> out = new LinkedHashMap<>();
    for (String c : CHAINS) out.put("sweep.threshold." + c, plain(threshold(c)));
    out.put("sweep.maxFeeRatioPct", plain(maxFeeRatioPct));
    out.put("sweep.evmCap.perAddress", plain(capPerAddress));
    out.put("sweep.evmCap.perBatch", plain(capPerBatch));
    for (String c : EVM_CHAINS) out.put("sweep.operatorMin." + c, plain(operatorMin.getOrDefault(c, BigDecimal.ZERO)));
    return out;
  }

  /** 校验页面提交的值；有问题时返回 null 并把问题放进 problems */
  public static SweepSettings normalize(Input in, List<Problem> problems) {
    if (in == null) {
      problems.add(new Problem("thresholds", "required"));
      return null;
    }
    Map<String, BigDecimal> th = new LinkedHashMap<>();
    for (String c : CHAINS) th.put(c, check("thresholds." + c, in.thresholds() == null ? null : in.thresholds().get(c), 6, new BigDecimal("1000000000"), problems));
    BigDecimal ratio = check("maxFeeRatioPct", in.maxFeeRatioPct(), 4, new BigDecimal("100"), problems);
    BigDecimal perAddress = check("evmCaps.perAddress", in.capPerAddress(), 6, new BigDecimal("1000000000"), problems);
    BigDecimal perBatch = check("evmCaps.perBatch", in.capPerBatch(), 6, new BigDecimal("1000000000"), problems);
    Map<String, BigDecimal> om = new LinkedHashMap<>();
    for (String c : EVM_CHAINS) om.put(c, check("operatorMin." + c, in.operatorMin() == null ? null : in.operatorMin().get(c), 8, new BigDecimal("1000000"), problems));
    if (!problems.isEmpty()) return null;
    if (perAddress.signum() > 0 && perBatch.signum() > 0 && perAddress.compareTo(perBatch) > 0) {
      problems.add(new Problem("evmCaps.perAddress", "aboveBatchCap"));
      return null;
    }
    return new SweepSettings(th, ratio, perAddress, perBatch, om, 0);
  }

  private static BigDecimal check(String field, String v, int scale, BigDecimal max, List<Problem> problems) {
    if (v == null || v.isBlank()) {
      problems.add(new Problem(field, "required"));
      return null;
    }
    try {
      BigDecimal d = new BigDecimal(v.strip());
      if (d.signum() < 0 || d.stripTrailingZeros().scale() > scale || d.compareTo(max) > 0) {
        problems.add(new Problem(field, "invalid"));
        return null;
      }
      return d.stripTrailingZeros();
    } catch (NumberFormatException e) {
      problems.add(new Problem(field, "invalid"));
      return null;
    }
  }

  private static BigDecimal num(String v, BigDecimal fallback) {
    if (v == null || v.isBlank()) return fallback;
    try {
      BigDecimal d = new BigDecimal(v.strip());
      return d.signum() < 0 ? fallback : d;
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  public static String plain(BigDecimal v) {
    return v == null ? "" : v.signum() == 0 ? "0" : v.stripTrailingZeros().toPlainString();
  }

  private static Map<String, BigDecimal> ordered(Map<String, BigDecimal> m, List<String> keys) {
    Map<String, BigDecimal> out = new LinkedHashMap<>();
    for (String k : keys) out.put(k, m.get(k));
    return out;
  }

  /** 修改前后有变化的字段（审计用）：字段名 → [前, 后] */
  public static Map<String, String[]> diff(SweepSettings before, SweepSettings after) {
    Map<String, String[]> out = new LinkedHashMap<>();
    Map<String, String> b = before.toConfig();
    Map<String, String> a = after.toConfig();
    for (var e : a.entrySet()) {
      String old = b.get(e.getKey());
      if (!e.getValue().equals(old)) out.put(e.getKey().substring(PREFIX.length()), new String[] {old, e.getValue()});
    }
    return out;
  }

  static List<String> keys() {
    return new ArrayList<>(DEFAULTS.toConfig().keySet());
  }
}
