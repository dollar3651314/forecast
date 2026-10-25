package com.forecast.app.admin.system;

import com.forecast.app.admin.security.AdminContext;
import com.forecast.app.admin.security.Audit;
import com.forecast.app.admin.security.IpRanges;
import com.forecast.app.admin.security.Menus;
import com.forecast.app.admin.security.Permission;
import com.forecast.app.common.config.ConfigKey;
import com.forecast.app.common.config.SystemConfig;
import com.forecast.app.common.error.BizException;
import com.forecast.app.common.error.ValidationException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 菜单、权限点字典、系统配置（后台方案第 2 节、G3、G7）。 */
@Service
class SystemService {

  /** 字段和原型 MenuNode 一致 */
  record MenuNode(String key, String path, String labelKey, String permission, int order, boolean visible, List<MenuNode> children) {}

  /** 字段和原型 PermissionDef 一致 */
  record PermissionView(String code, String module, boolean highRisk) {}

  /** 字段和原型 SystemConfig 一致 */
  record ConfigView(String key, String value, String unit, String group, String updatedAt) {}

  /** 参数设置页的一项（forecast S3）：section 是 fee（fee:edit）或 betting（betting_params:edit） */
  record ParamView(String key, String value, String unit, String group, String section, String updatedAt) {}

  private final SystemMapper mapper;
  private final SystemConfig config;
  private final Audit audit;
  private final Clock clock;

  SystemService(SystemMapper mapper, SystemConfig config, Audit audit, Clock clock) {
    this.mapper = mapper;
    this.config = config;
    this.audit = audit;
    this.clock = clock;
  }

  // ---------- 菜单 ----------

  /** 全部菜单（菜单管理页）：默认顺序 10、20、30……，后台可以改顺序和是否显示 */
  @Transactional(readOnly = true)
  List<MenuNode> menus() {
    Map<String, SystemMapper.MenuRow> saved = mapper.menus().stream().collect(Collectors.toMap(m -> m.menuKey, Function.identity()));
    return nodes(Menus.TREE, saved);
  }

  /** 当前管理员能看到的菜单：显示的、有权限的叶子；分组下一个都看不到时整个分组不显示（AC-32） */
  List<MenuNode> visibleFor(AdminContext ctx) {
    return filter(menus(), ctx);
  }

  private static List<MenuNode> filter(List<MenuNode> nodes, AdminContext ctx) {
    List<MenuNode> out = new ArrayList<>();
    for (MenuNode n : nodes) {
      if (!n.visible()) continue;
      if (n.children().isEmpty()) {
        if (n.permission() != null && Permission.of(n.permission()).map(ctx::has).orElse(false)) out.add(n);
      } else {
        List<MenuNode> kids = filter(n.children(), ctx);
        if (!kids.isEmpty()) out.add(new MenuNode(n.key(), n.path(), n.labelKey(), n.permission(), n.order(), true, kids));
      }
    }
    return out;
  }

  private static List<MenuNode> nodes(List<Menus.Item> items, Map<String, SystemMapper.MenuRow> saved) {
    List<MenuNode> out = new ArrayList<>();
    for (int i = 0; i < items.size(); i++) {
      Menus.Item it = items.get(i);
      SystemMapper.MenuRow s = saved.get(it.key());
      out.add(new MenuNode(it.key(), it.path(), it.labelKey(), it.permission() == null ? null : it.permission().code, s == null ? (i + 1) * 10 : s.sortOrder,
          s == null || s.visible, nodes(it.children(), saved)));
    }
    out.sort(Comparator.comparingInt(MenuNode::order));
    return out;
  }

  private static boolean exists(List<Menus.Item> items, String key) {
    for (Menus.Item it : items) if (it.key().equals(key) || exists(it.children(), key)) return true;
    return false;
  }

  @Transactional
  void updateMenu(String key, Integer order, Boolean visible) {
    audit.run("menu.update", key, false, null, () -> {
      if (!exists(Menus.TREE, key)) throw new BizException("NOT_FOUND", "菜单不存在");
      if (order == null || order < 0 || order > 100_000) throw new ValidationException(List.of(new ValidationException.Field("order", "invalid")));
      MenuNode before = find(menus(), key);
      boolean v = visible == null ? before.visible() : visible;
      mapper.saveMenu(key, order, v);
      return Audit.Change.of(null, Map.of("order", before.order(), "visible", before.visible()), Map.of("order", order, "visible", v));
    });
  }

  private static MenuNode find(List<MenuNode> nodes, String key) {
    for (MenuNode n : nodes) {
      if (n.key().equals(key)) return n;
      MenuNode c = find(n.children(), key);
      if (c != null) return c;
    }
    return null;
  }

  // ---------- 权限点 ----------

  List<PermissionView> permissions() {
    return Arrays.stream(Permission.values()).map(p -> new PermissionView(p.code, p.module, p.highRisk)).toList();
  }

  // ---------- 系统配置 ----------

  List<ConfigView> configs() {
    return config.list().stream().filter(e -> e.key().configPage).map(e -> new ConfigView(e.key().key, e.value(), e.key().unit.api(), e.key().group, AdminUserService.time(e.updatedAt()))).toList();
  }

  /** 三条链的提现手续费（网络名 → 配置项），和 wallet.Chain 的三条链一致 */
  static final Map<String, ConfigKey> WITHDRAW_FEES = new java.util.LinkedHashMap<>();

  static {
    WITHDRAW_FEES.put("ERC20", ConfigKey.WITHDRAW_FEE_ERC20);
    WITHDRAW_FEES.put("TRC20", ConfigKey.WITHDRAW_FEE_TRC20);
    WITHDRAW_FEES.put("BSC", ConfigKey.WITHDRAW_FEE_BSC);
  }

  /**
   * 单笔最小提现金额必须大于每条链的提现手续费（需求 RQ-16、第 3.11 节、AC-61），否则到账为 0 或负数。改最小金额时和三条链的手续费比，
   * 改某条链的手续费时和最小金额比；不满足时拒绝保存，字段错误里写明是哪条链（field = 网络名，code = minNotAboveFee / feeNotBelowMin）。
   * 先锁最小金额、再锁手续费（两种改法的锁顺序相同，不会死锁）；读的是数据库里的值，不走缓存。
   * 某一项在数据库里没有行（迁移 V1 都已建好，正常不会发生）时跳过这一项：提现时 WithdrawService 还会检查"金额必须大于手续费"。
   */
  private void checkWithdrawMinAboveFees(ConfigKey k, String newValue) {
    boolean isMin = k == ConfigKey.WITHDRAW_MIN_AMOUNT;
    boolean isFee = WITHDRAW_FEES.containsValue(k);
    if (!isMin && !isFee) return;
    String minText = isMin ? newValue : config.lockedValue(ConfigKey.WITHDRAW_MIN_AMOUNT);
    if (isMin) config.lockedValue(ConfigKey.WITHDRAW_MIN_AMOUNT);
    if (minText == null) return;
    java.math.BigDecimal min = new java.math.BigDecimal(minText);
    ValidationException.Collector errs = new ValidationException.Collector();
    for (Map.Entry<String, ConfigKey> e : WITHDRAW_FEES.entrySet()) {
      if (isFee && e.getValue() != k) continue;
      String feeText = e.getValue() == k ? newValue : config.lockedValue(e.getValue());
      if (feeText == null) continue;
      if (min.compareTo(new java.math.BigDecimal(feeText)) <= 0) errs.add(e.getKey(), isMin ? "minNotAboveFee" : "feeNotBelowMin");
    }
    errs.throwIfAny();
  }

  // ---------- 参数设置（forecast S3，需求说明书第 6.2 节） ----------

  /** 参数设置页某一组的全部项（fee：品类默认费率；betting：下注限额、截止提前量、争议窗口、KYC 阈值、单笔最小提现） */
  List<ParamView> params(ConfigKey.Params section) {
    return config.list().stream().filter(e -> e.key().params == section).map(this::paramView).toList();
  }

  private ParamView paramView(SystemConfig.Entry e) {
    return new ParamView(e.key().key, e.value(), e.key().unit.api(), e.key().group, e.key().params.name().toLowerCase(), AdminUserService.time(e.updatedAt()));
  }

  /**
   * 改一项参数（高危，原因必填，审计写前 → 后）。键必须属于这一组（fee 组要 fee:edit、betting 组要 betting_params:edit，由接口的权限点保证）；
   * 值按 {@link ConfigKey#normalize} 校验范围和小数位；单笔最小提现照样和三条链的手续费互相校验。
   * {@code expected}：页面打开时看到的值；传了而数据库里的值已经不同（别人刚改过），返回 409 {@code CONFIG_CHANGED}，不覆盖（需求第 6.2 节"有人同时修改时后保存的提示冲突"）。
   */
  @Transactional
  ParamView updateParam(AdminContext actor, ConfigKey.Params section, String key, String value, String reason, String expected) {
    String r = Audit.requireReason(reason);
    return audit.run("params.update", key, true, r, () -> {
      ConfigKey k = ConfigKey.of(key).filter(x -> x.params == section).orElseThrow(() -> new BizException("NOT_FOUND", "参数不存在"));
      String v = k.normalize(value).orElseThrow(() -> new ValidationException(List.of(new ValidationException.Field("value", "invalid"))));
      String current = config.lockedValue(k);
      if (expected != null && !sameValue(expected, current)) throw new BizException("CONFIG_CHANGED", "参数已被别人修改，请刷新后再改");
      checkWithdrawMinAboveFees(k, v);
      String old = config.set(k, v, actor.adminId(), LocalDateTime.now(clock));
      ParamView view = params(section).stream().filter(c -> c.key().equals(k.key)).findFirst().orElseThrow();
      return Audit.Change.of(view, Map.of("value", old == null ? "" : old), Map.of("value", v));
    });
  }

  /** 数值按大小比（"100" 和 "100.00" 相同），文字按原样比 */
  private static boolean sameValue(String a, String b) {
    if (b == null) return a.isBlank();
    try {
      return new java.math.BigDecimal(a.strip()).compareTo(new java.math.BigDecimal(b.strip())) == 0;
    } catch (NumberFormatException e) {
      return a.strip().equals(b.strip());
    }
  }

  /**
   * 改系统配置（高危，必须填原因）。单笔最小提现和三条链的手续费互相校验（{@link #checkWithdrawMinAboveFees}）。值按单位校验；充值确认数不能低于现在的默认值；
   * IP 白名单每一项都要是合法的 IP 或网段，并且<b>必须包含当前操作人的 IP</b>，防止把自己锁在外面（G3）。
   */
  @Transactional
  ConfigView updateConfig(AdminContext actor, String key, String value, String reason) {
    String r = Audit.requireReason(reason);
    return audit.run("config.update", key, true, r, () -> {
      ConfigKey k = ConfigKey.of(key).filter(x -> x.configPage).orElseThrow(() -> new BizException("NOT_FOUND", "配置项不存在"));
      String v = k.normalize(value).orElseThrow(() -> new ValidationException(List.of(new ValidationException.Field("value", "invalid"))));
      checkWithdrawMinAboveFees(k, v);
      if (k == ConfigKey.ADMIN_IP_WHITELIST && !v.isEmpty()) {
        IpRanges ranges = IpRanges.parse(v).orElseThrow(() -> new ValidationException(List.of(new ValidationException.Field("value", "invalid"))));
        if (!ranges.contains(actor.ip())) throw new BizException("WHITELIST_EXCLUDES_SELF", "白名单不包含你当前的 IP，保存后你会无法访问后台");
      }
      String old = config.set(k, v, actor.adminId(), LocalDateTime.now(clock));
      ConfigView view = configs().stream().filter(c -> c.key().equals(k.key)).findFirst().orElseThrow();
      return Audit.Change.of(view, Map.of("value", old == null ? "" : old), Map.of("value", v));
    });
  }
}
