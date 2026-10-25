package com.forecast.app.admin.ops;

import com.forecast.app.account.api.CustomerAdminApi;
import com.forecast.app.admin.security.AdminContext;
import com.forecast.app.admin.security.AdminNames;
import com.forecast.app.admin.security.Audit;
import com.forecast.app.common.config.ConfigKey;
import com.forecast.app.common.config.SystemConfig;
import com.forecast.app.common.error.BizException;
import com.forecast.app.common.error.ValidationException;
import com.forecast.app.wallet.api.SweepAdminApi;
import com.forecast.app.wallet.api.SweepSettings;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import org.springframework.stereotype.Service;

/**
 * 后台"资金归集"（v2 第一阶段：以太坊、BSC；技术方案/v2-半自动归集.md 第 4.9、6 节）。字段和原型 {@code web/admin/src/api/types.ts} 的 Sweep* 一致，
 * TRON 专用的字段（激活、租能量、离线签名）这一阶段不返回，前端真实接口模式下显示"下一阶段"。
 * <ul>
 *   <li>执行、重试：权限 {@code sweep:operate}，高危、原因必填，审计 {@code sweep.run} / {@code sweep.retry}（对象是批次号）。</li>
 *   <li>设置：权限 {@code sweep:config}，高危、原因必填，带版本号（{@code sweep.version}，版本不对 409 {@code CONFIG_CHANGED}），审计 {@code sweep.settings_update}
 *       （修改前 / 修改后只有变化的项）。以太坊、BSC 的冷钱包写在合约里，这里只读。</li>
 * </ul>
 */
@Service
class SweepOps {

  static final String AUDIT_RUN = "sweep.run";
  static final String AUDIT_RETRY = "sweep.retry";
  static final String AUDIT_SETTINGS = "sweep.settings_update";

  record OverviewView(String chain, String mode, boolean available, String unavailable, String pendingUsdt, int eligibleCount, int belowCount,
      String destination, String operatorBalance, String gasAsset, String lastSweepAt, String activeBatch, String checkedAt) {}

  record CandidateView(long userId, String customerName, String address, String usdt, boolean deploy, String estFee, String estFeeUsd, String skip) {}

  record TotalsView(int count, String usdt, String fee, String feeUsd, String feePct, int skipped) {}

  record PreviewView(String chain, String mode, String destination, String threshold, String maxFeeRatioPct, String gasPrice, String gasAsset,
      List<CandidateView> candidates, TotalsView totals, List<String> warnings) {}

  record BatchView(String no, String chain, String mode, String status, String trigger, int addressCount, String expectedUsdt, String sweptUsdt, String fee,
      String gasAsset, String destination, String createdBy, String createdAt, String finishedAt, String expiresAt, String txHash) {}

  record ItemView(int itemNo, long userId, String customerName, String address, String amount, String balanceBefore, boolean deploy, String status,
      String txHash, String failReason) {}

  record EventView(String time, String actor, String text) {}

  record BatchDetailView(String no, String chain, String mode, String status, String trigger, int addressCount, String expectedUsdt, String sweptUsdt,
      String fee, String gasAsset, String destination, String createdBy, String createdAt, String finishedAt, String expiresAt, String txHash, String reason,
      String failReason, List<ItemView> items, List<EventView> events) {}

  record EvmView(boolean configured, String problem, int schemeVer, String parent, String factory, String implementation, String batcher, String operator,
      boolean operatorReady, String operatorProblem, Map<String, String> operatorBalances, String readAt) {}

  record CapsView(String perAddress, String perBatch) {}

  record SettingsView(Map<String, String> thresholds, String maxFeeRatioPct, CapsView evmCaps, Map<String, String> operatorMin, Map<String, String> defaults,
      EvmView evm, long version, String updatedAt, String updatedBy) {}

  record SaveBody(Map<String, String> thresholds, String maxFeeRatioPct, CapsView evmCaps, Map<String, String> operatorMin, String reason, Long expectedVersion) {}

  private final SweepAdminApi sweep;
  private final SystemConfig config;
  private final CustomerAdminApi customers;
  private final AdminNames names;
  private final Audit audit;
  private final Clock clock;

  SweepOps(SweepAdminApi sweep, SystemConfig config, CustomerAdminApi customers, AdminNames names, Audit audit, Clock clock) {
    this.sweep = sweep;
    this.config = config;
    this.customers = customers;
    this.names = names;
    this.audit = audit;
    this.clock = clock;
  }

  List<OverviewView> overview() {
    return sweep.overview().stream().map(o -> new OverviewView(o.chain(), o.mode().toLowerCase(), o.available(), o.unavailable(), Ops.money(o.pendingUsdt()),
        o.eligibleCount(), o.belowCount(), o.destination(), Ops.money(o.operatorBalance()), o.gasAsset(), Ops.time(o.lastSweepAt()),
        o.activeBatchNo() == null ? null : String.valueOf(o.activeBatchNo()), Ops.time(o.checkedAt()))).toList();
  }

  PreviewView preview(String chain) {
    SweepAdminApi.Preview p = sweep.preview(chain);
    Map<Long, String> who = customerNames(p.candidates().stream().map(SweepAdminApi.Candidate::userId).toList());
    return new PreviewView(p.chain(), p.mode().toLowerCase(), p.destination(), Ops.money(p.threshold()), Ops.money(p.maxFeeRatioPct()), p.gasPrice(),
        p.gasAsset(), p.candidates().stream().map(c -> new CandidateView(c.userId(), who.get(c.userId()), c.address(), Ops.money(c.usdt()), c.deploy(),
            Ops.money(c.estFee()), Ops.money(c.estFeeUsd()), c.skip())).toList(),
        new TotalsView(p.totals().count(), Ops.money(p.totals().usdt()), Ops.money(p.totals().fee()), Ops.money(p.totals().feeUsd()), Ops.money(p.totals().feePct()),
            p.totals().skipped()),
        p.warnings());
  }

  String run(AdminContext actor, String chain, String reason) {
    String r = Audit.requireReason(reason);
    String c = chain == null ? "" : chain.strip().toUpperCase();
    return audit.run(AUDIT_RUN, c, true, r, () -> {
      long no = sweep.run(c, actor.adminId(), r);
      SweepAdminApi.Batch b = sweep.batch(no).map(SweepAdminApi.BatchDetail::batch).orElseThrow();
      return Audit.Change.of(String.valueOf(no), null, after(b));
    });
  }

  String retry(AdminContext actor, long no, String reason) {
    String r = Audit.requireReason(reason);
    return audit.run(AUDIT_RETRY, String.valueOf(no), true, r, () -> {
      long fresh = sweep.retry(no, actor.adminId(), r);
      SweepAdminApi.Batch b = sweep.batch(fresh).map(SweepAdminApi.BatchDetail::batch).orElseThrow();
      Map<String, Object> after = after(b);
      after.put("retryOf", String.valueOf(no));
      return Audit.Change.of(String.valueOf(fresh), null, after);
    });
  }

  private static Map<String, Object> after(SweepAdminApi.Batch b) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("batch", String.valueOf(b.no()));
    m.put("chain", b.chain());
    m.put("status", b.status());
    m.put("addresses", b.addressCount());
    m.put("usdt", Ops.money(b.expectedUsdt()));
    m.put("destination", b.destination());
    if (b.txHash() != null) m.put("txHash", b.txHash());
    return m;
  }

  Ops.Page<BatchView> batches(String chain, int page, int pageSize) {
    Ops.Slice s = Ops.slice(page, pageSize);
    SweepAdminApi.BatchPage p = sweep.batches(Ops.blank(chain), s.offset(), s.size());
    Map<Long, String> who = names.of(p.items().stream().map(SweepAdminApi.Batch::createdBy).filter(Objects::nonNull).toList());
    return new Ops.Page<>(p.items().stream().map(b -> batchView(b, who)).toList(), p.total(), s.page(), s.size());
  }

  private static BatchView batchView(SweepAdminApi.Batch b, Map<Long, String> who) {
    return new BatchView(String.valueOf(b.no()), b.chain(), b.mode().toLowerCase(), b.status().toLowerCase(), b.trigger().toLowerCase(), b.addressCount(),
        Ops.money(b.expectedUsdt()), Ops.money(b.sweptUsdt()), Ops.money(b.fee()), b.feeAsset(), b.destination(),
        b.createdBy() == null ? "system" : who.getOrDefault(b.createdBy(), String.valueOf(b.createdBy())), Ops.time(b.createdAt()), Ops.time(b.finishedAt()),
        Ops.time(b.expiresAt()), b.txHash());
  }

  BatchDetailView batch(long no) {
    SweepAdminApi.BatchDetail d = sweep.batch(no).orElseThrow(() -> new BizException("NOT_FOUND", "批次不存在"));
    SweepAdminApi.Batch b = d.batch();
    List<Long> admins = new ArrayList<>();
    if (b.createdBy() != null) admins.add(b.createdBy());
    for (SweepAdminApi.Event e : d.events()) if (e.actor().matches("\\d{1,18}")) admins.add(Long.parseLong(e.actor()));
    Map<Long, String> who = names.of(admins);
    Map<Long, String> cs = customerNames(d.items().stream().map(SweepAdminApi.Item::userId).toList());
    BatchView v = batchView(b, who);
    return new BatchDetailView(v.no(), v.chain(), v.mode(), v.status(), v.trigger(), v.addressCount(), v.expectedUsdt(), v.sweptUsdt(), v.fee(), v.gasAsset(),
        v.destination(), v.createdBy(), v.createdAt(), v.finishedAt(), v.expiresAt(), v.txHash(), d.reason(), d.failReason(),
        d.items().stream().map(i -> new ItemView(i.itemNo(), i.userId(), cs.get(i.userId()), i.address(), Ops.money(i.swept() != null ? i.swept() : i.amount()),
            Ops.money(i.amount()), i.deploy(), i.status().toLowerCase(), i.txHash(), i.failReason())).toList(),
        d.events().stream().map(e -> new EventView(Ops.time(e.time()), e.actor().matches("\\d{1,18}") ? who.getOrDefault(Long.parseLong(e.actor()), e.actor())
            : e.actor(), e.text())).toList());
  }

  private Map<Long, String> customerNames(List<Long> ids) {
    Map<Long, String> out = new LinkedHashMap<>();
    if (ids.isEmpty()) return out;
    customers.customers(ids).forEach((k, v) -> out.put(k, v.name() == null ? v.email() : v.name()));
    return out;
  }

  // ---------- 设置 ----------

  SettingsView settings() {
    config.refresh();
    SweepSettings s = SweepSettings.parse(config.snapshot());
    SweepAdminApi.Contracts c = sweep.contracts();
    SystemConfig.Entry v = config.list().stream().filter(e -> e.key() == ConfigKey.SWEEP_VERSION).findFirst().orElse(null);
    String by = v == null || v.updatedBy() == null ? null : names.of(List.of(v.updatedBy())).get(v.updatedBy());
    String at = v == null || v.updatedBy() == null ? null : Ops.time(v.updatedAt());
    Function<Map<String, BigDecimal>, Map<String, String>> plain = m -> {
      Map<String, String> out = new LinkedHashMap<>();
      m.forEach((k, x) -> out.put(k, SweepSettings.plain(x)));
      return out;
    };
    Map<String, String> defaults = new LinkedHashMap<>();
    SweepSettings.DEFAULTS.toConfig().forEach((k, x) -> defaults.put(k.substring(SweepSettings.PREFIX.length()), x));
    Map<String, String> balances = new LinkedHashMap<>();
    c.operatorBalances().forEach((k, x) -> balances.put(k, Ops.money(x)));
    return new SettingsView(plain.apply(s.thresholds()), SweepSettings.plain(s.maxFeeRatioPct()),
        new CapsView(SweepSettings.plain(s.capPerAddress()), SweepSettings.plain(s.capPerBatch())), plain.apply(s.operatorMin()), defaults,
        new EvmView(c.configured(), c.problem(), c.schemeVer(), c.parent(), c.factory(), c.implementation(), c.batcher(), c.operator(), c.operatorReady(),
            c.operatorProblem(), balances, Ops.time(c.readAt())),
        s.version(), at, by);
  }

  SettingsView save(AdminContext actor, SaveBody body) {
    SaveBody b = body == null ? new SaveBody(null, null, null, null, null, null) : body;
    String reason = Audit.requireReason(b.reason());
    if (b.expectedVersion() == null) throw new ValidationException(List.of(new ValidationException.Field("expectedVersion", "required")));
    long expected = b.expectedVersion();
    return audit.run(AUDIT_SETTINGS, "v" + (expected + 1), true, reason, () -> {
      List<SweepSettings.Problem> problems = new ArrayList<>();
      SweepSettings next = SweepSettings.normalize(new SweepSettings.Input(b.thresholds(), b.maxFeeRatioPct(), b.evmCaps() == null ? null : b.evmCaps().perAddress(),
          b.evmCaps() == null ? null : b.evmCaps().perBatch(), b.operatorMin()), problems);
      if (next == null) throw new ValidationException(problems.stream().map(p -> new ValidationException.Field(p.field(), p.code())).toList());
      Map<ConfigKey, String> values = new LinkedHashMap<>();
      next.toConfig().forEach((k, v) -> values.put(ConfigKey.of(k).orElseThrow(), v));
      LocalDateTime now = LocalDateTime.now(clock.withZone(ZoneOffset.UTC));
      Map<ConfigKey, String[]> changed = config.setVersioned(ConfigKey.SWEEP_VERSION, expected, values, actor.adminId(), now);
      if (changed.isEmpty()) throw new BizException("NO_CHANGE", "没有修改");
      Map<String, Object> before = new LinkedHashMap<>();
      Map<String, Object> after = new LinkedHashMap<>();
      changed.forEach((k, v) -> {
        String f = k.key.substring(SweepSettings.PREFIX.length());
        before.put(f, v[0] == null ? "" : v[0]);
        after.put(f, v[1]);
      });
      return Audit.Change.of(settings(), before, after);
    });
  }
}
