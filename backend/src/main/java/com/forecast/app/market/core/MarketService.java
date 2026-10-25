package com.forecast.app.market.core;

import static com.forecast.app.market.core.MarketFormats.instant;
import static com.forecast.app.market.core.MarketFormats.iso;
import static com.forecast.app.market.core.MarketFormats.ldt;
import static com.forecast.app.market.core.MarketFormats.money;
import static com.forecast.app.market.core.MarketFormats.percent;

import com.forecast.app.common.config.SystemConfig;
import com.forecast.app.common.error.BizException;
import com.forecast.app.common.error.ValidationException;
import com.forecast.app.common.id.Snowflake;
import com.forecast.app.market.api.I18nText;
import com.forecast.app.market.api.MarketAdminApi;
import com.forecast.app.market.api.MarketApi;
import com.forecast.app.market.api.MarketCategory;
import com.forecast.app.market.api.MarketListener;
import com.forecast.app.market.api.MarketPoolView;
import com.forecast.app.market.api.MarketResolutionView;
import com.forecast.app.market.api.MarketStatus;
import com.forecast.app.market.api.ResolutionMode;
import com.forecast.app.market.api.SettlePhase;
import com.forecast.app.ledger.api.MarketLabels;
import com.forecast.app.notification.api.NotifyApi;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 市场（forecast S3，需求说明书第 3.2、3.7、3.8、4.1 节，技术方案/v1-架构方案.md 第 3.3、5 节）。
 * <ul>
 *   <li>状态只按 {@link MarketStateMachine} 的 17 条边走；每次跳转是带旧状态的条件更新，同一事务写 {@code market_status_log}，
 *       重复触发、多实例同时触发都只生效一次。</li>
 *   <li>上架后题目、选项、时间、裁决来源不能改（Mapper 的修改语句只对草稿生效）；只能发补充说明、提前截止、改费率。</li>
 *   <li>截止任务拿市场行排他锁，下注拿共享锁（{@link #lockForBet}），截止时定格奖池、有效选项不足 2 个同一事务作废（E3、E4）。</li>
 *   <li>审计在 admin 模块（{@code admin/ops/MarketOps}）；这里只管业务。</li>
 * </ul>
 */
@Service
public class MarketService implements MarketApi, MarketAdminApi, MarketLabels {

  private static final Logger log = LoggerFactory.getLogger(MarketService.class);

  /** 状态历史里系统操作的 actor */
  public static final String SYSTEM = "SYSTEM";
  /** 截止时有效选项不足 2 个（E3、E4）的作废原因代码 */
  public static final String VOID_NOT_ENOUGH_OPTIONS = "NOT_ENOUGH_OPTIONS";
  /** 客户端能看到的状态（草稿不出现） */
  static final Set<MarketStatus> VISIBLE = EnumSet.complementOf(EnumSet.of(MarketStatus.DRAFT));
  /** 客户端详情里有裁决信息（resolution 字段）的状态（S7 起含公示中、争议中、已作废） */
  static final Set<MarketStatus> RESOLUTION_VISIBLE = EnumSet.of(MarketStatus.PUBLICIZING, MarketStatus.DISPUTED, MarketStatus.SETTLED, MarketStatus.VOIDED);
  /** 截止任务、事件结束任务每轮最多处理几个市场（处理不完的下一轮接着处理） */
  static final int BATCH = 200;
  /** 发补充说明、文字长度上限 */
  static final int TEXT_MAX = 2000;
  static final int RULES_MAX = 10_000;

  private final MarketMapper mapper;
  private final SystemConfig config;
  private final Snowflake ids;
  private final Clock clock;
  private final NotifyApi notify;
  private final ObjectProvider<MarketPoolView> pool;
  private final ObjectProvider<MarketListener> listeners;
  private final ObjectProvider<MarketResolutionView> resolution;
  private final ObjectMapper json;
  private final TransactionTemplate tx;

  MarketService(MarketMapper mapper, SystemConfig config, Snowflake ids, Clock clock, NotifyApi notify, ObjectProvider<MarketPoolView> pool,
      ObjectProvider<MarketListener> listeners, ObjectProvider<MarketResolutionView> resolution, ObjectMapper json, PlatformTransactionManager tm) {
    this.mapper = mapper;
    this.config = config;
    this.ids = ids;
    this.clock = clock;
    this.notify = notify;
    this.pool = pool;
    this.listeners = listeners;
    this.resolution = resolution;
    this.json = json;
    this.tx = new TransactionTemplate(tm);
  }

  LocalDateTime now() {
    return LocalDateTime.now(clock).truncatedTo(ChronoUnit.MILLIS);
  }

  // =====================================================================
  // 给其他模块（MarketApi）
  // =====================================================================

  @Override
  @Transactional(readOnly = true)
  public Optional<MarketRef> find(long publicNo) {
    MarketRows.Market m = mapper.findByPublicNo(publicNo);
    return m == null ? Optional.empty() : Optional.of(ref(m));
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<MarketRef> findById(long marketId) {
    MarketRows.Market m = mapper.findById(marketId);
    return m == null ? Optional.empty() : Optional.of(ref(m));
  }

  @Override
  @Transactional(readOnly = true)
  public Map<Long, MarketRef> refs(Collection<Long> marketIds) {
    if (marketIds == null || marketIds.isEmpty()) return Map.of();
    Map<Long, MarketRef> out = new LinkedHashMap<>();
    for (MarketRows.Market m : mapper.findByIds(List.copyOf(Set.copyOf(marketIds)))) out.put(m.id, ref(m));
    return out;
  }

  @Override
  @Transactional(readOnly = true)
  public List<OptionRef> options(long marketId) {
    return mapper.options(marketId).stream().map(MarketService::optionRef).toList();
  }

  @Override
  @Transactional(readOnly = true)
  public Map<Long, OptionRef> optionsById(Collection<Long> optionIds) {
    if (optionIds == null || optionIds.isEmpty()) return Map.of();
    Map<Long, OptionRef> out = new LinkedHashMap<>();
    for (MarketRows.Option o : mapper.optionsByIds(List.copyOf(Set.copyOf(optionIds)))) out.put(o.id, optionRef(o));
    return out;
  }

  /** 客户资金流水里的市场（ledger 的 {@link MarketLabels}，AC-39：每条流水能点到对应市场） */
  @Override
  @Transactional(readOnly = true)
  public Map<Long, MarketLabels.MarketLabel> labels(Collection<Long> marketIds) {
    Map<Long, MarketLabels.MarketLabel> out = new LinkedHashMap<>();
    refs(marketIds).forEach((id, r) -> out.put(id, new MarketLabels.MarketLabel(String.valueOf(r.publicNo()), r.title().en(), r.title().zhCN(), r.title().zhTW())));
    return out;
  }

  private static MarketRef ref(MarketRows.Market m) {
    return new MarketRef(m.id, m.publicNo, status(m), MarketCategory.valueOf(m.category), title(m), instant(m.openAt), instant(m.closeAt), instant(m.eventEndAt),
        m.feeRate, m.userCap);
  }

  private static OptionRef optionRef(MarketRows.Option o) {
    return new OptionRef(o.id, o.publicNo, o.marketId, o.seq, label(o));
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public BetTarget lockForBet(long marketPublicNo, long optionPublicNo, Instant receivedAt) {
    MarketRows.Market m = mapper.lockForShareByPublicNo(marketPublicNo);
    if (m == null || status(m) == MarketStatus.DRAFT) throw new BizException("NOT_FOUND", "市场不存在");
    MarketStatus s = status(m);
    if (s == MarketStatus.CANCELLED) throw new BizException("MARKET_CANCELLED", "市场已取消");
    // 收到时间 < 截止时间 才接受，正好等于截止时间的拒绝（AC-15）；截止任务已经跑过的，状态不再是已上架 / 已暂停
    if ((s != MarketStatus.LISTED && s != MarketStatus.PAUSED) || !receivedAt.isBefore(instant(m.closeAt))) {
      throw new BizException("MARKET_CLOSED", "市场已截止下注");
    }
    if (s == MarketStatus.PAUSED) throw new BizException("MARKET_PAUSED", "市场已暂停");
    if (receivedAt.isBefore(instant(m.openAt))) throw new BizException("MARKET_NOT_OPEN", "市场还没到开放下注时间");
    MarketRows.Option o = mapper.option(m.id, optionPublicNo);
    if (o == null) throw new BizException("OPTION_NOT_FOUND", "选项不存在");
    return new BetTarget(m.id, m.publicNo, o.id, o.publicNo, MarketCategory.valueOf(m.category), m.feeRate, m.userCap, instant(m.closeAt), title(m), label(o));
  }

  @Override
  @Transactional
  public boolean transition(long marketId, MarketStatus from, MarketStatus to, String actor, String reason) {
    return move(marketId, from, to, actor, reason, null, now());
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public SettleTarget lockForSettle(long marketId) {
    MarketRows.Market m = mapper.lockById(marketId);
    if (m == null || m.deletedAt != null || status(m) == MarketStatus.DRAFT) throw new BizException("NOT_FOUND", "市场不存在");
    List<OptionRef> opts = mapper.options(m.id).stream().map(MarketService::optionRef).toList();
    return new SettleTarget(m.id, m.publicNo, status(m), SettlePhase.valueOf(m.settlePhase), MarketCategory.valueOf(m.category), m.feeRate,
        instant(m.closeAt), m.actualStartAt == null ? null : instant(m.actualStartAt), m.winningOptionId, title(m), opts);
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean recordResult(long marketId, long winningOptionId, Instant actualStartAt, String resolvedBy) {
    if (mapper.options(marketId).stream().noneMatch(o -> o.id == winningOptionId)) throw new BizException("OPTION_NOT_FOUND", "选项不存在");
    return mapper.setResult(marketId, winningOptionId, actualStartAt == null ? null : ldt(actualStartAt.truncatedTo(ChronoUnit.MILLIS)),
        resolvedBy == null ? null : cut(resolvedBy, 32), now()) == 1;
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean casSettlePhase(long marketId, SettlePhase from, SettlePhase to) {
    boolean ok = mapper.casSettlePhase(marketId, from.name(), to.name(), now()) == 1;
    // 进入"结算中"、完成或异常：客户端详情里的状态变了，推送一次（状态本身没变）
    if (ok) {
      MarketRows.Market m = mapper.findById(marketId);
      if (m != null) poolChanged(marketId, status(m));
    }
    return ok;
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<AutoRef> autoRef(long marketId) {
    MarketRows.Market m = mapper.findById(marketId);
    if (m == null || m.deletedAt != null) return Optional.empty();
    List<OptionRef> opts = mapper.options(m.id).stream().map(MarketService::optionRef).toList();
    return Optional.of(new AutoRef(m.id, m.publicNo, status(m), MarketCategory.valueOf(m.category), ResolutionMode.of(m.resolutionMode), sourceRef(m),
        instant(m.eventStartAt), instant(m.eventEndAt), title(m), opts, m.autogenId));
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<PlatformRef> platformRef(long marketId) {
    MarketRows.Market m = mapper.findById(marketId);
    if (m == null || m.deletedAt != null) return Optional.empty();
    return Optional.of(platformRef(m));
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public PlatformRef lockPlatform(long marketId) {
    MarketRows.Market m = mapper.lockById(marketId);
    if (m == null || m.deletedAt != null || status(m) == MarketStatus.DRAFT) throw new BizException("NOT_FOUND", "市场不存在");
    return platformRef(m);
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean publicize(long marketId, long optionId, Instant actualStartAt, String resolvedBy, Instant disputeDeadlineAt, String actor) {
    if (mapper.options(marketId).stream().noneMatch(o -> o.id == optionId)) throw new BizException("OPTION_NOT_FOUND", "选项不存在");
    MarketRows.Market m = mapper.findById(marketId);
    if (m == null || !"NONE".equals(m.settlePhase)) return false;
    LocalDateTime now = now();
    if (!move(marketId, MarketStatus.PENDING_RESOLUTION, MarketStatus.PUBLICIZING, actor, null, null, now)) return false;
    if (mapper.setPublicity(marketId, optionId, actualStartAt == null ? null : ldt(actualStartAt.truncatedTo(ChronoUnit.MILLIS)),
        resolvedBy == null ? null : cut(resolvedBy, 32), ldt(disputeDeadlineAt.truncatedTo(ChronoUnit.MILLIS)), now) != 1) {
      throw new IllegalStateException("公示时市场被别人改了：" + marketId);
    }
    return true;
  }

  private PlatformRef platformRef(MarketRows.Market m) {
    List<OptionRef> opts = mapper.options(m.id).stream().map(MarketService::optionRef).toList();
    return new PlatformRef(m.id, m.publicNo, status(m), SettlePhase.valueOf(m.settlePhase), MarketCategory.valueOf(m.category), ResolutionMode.of(m.resolutionMode),
        title(m), opts, instant(m.closeAt), instant(m.eventStartAt), instant(m.eventEndAt), m.disputeWindowMin, m.winningOptionId,
        m.actualStartAt == null ? null : instant(m.actualStartAt), m.resolvedBy, m.disputeDeadlineAt == null ? null : instant(m.disputeDeadlineAt), m.voidReason);
  }

  /**
   * 状态跳转的唯一入口：只允许 17 条边；带旧状态的条件更新（0 行 = 状态已被别人改过，返回 false，什么都不做）；同一事务写状态历史；
   * 进入取消、作废时通知 {@link MarketListener#onRefundRequired}（S5 建退款单）。
   */
  private boolean move(long id, MarketStatus from, MarketStatus to, String actor, String reason, Long listedBy, LocalDateTime now) {
    MarketStateMachine.check(from, to);
    String cut = reason == null ? null : cut(reason, 500);
    int n = mapper.transition(id, from.name(), to.name(), now, listedBy, to == MarketStatus.CANCELLED ? cut : null, to == MarketStatus.VOIDED ? cut : null);
    if (n == 0) return false;
    MarketRows.StatusLog l = new MarketRows.StatusLog();
    l.marketId = id;
    l.fromStatus = from.name();
    l.toStatus = to.name();
    l.actor = actor;
    l.reason = cut;
    l.createdAt = now;
    mapper.insertStatusLog(l);
    // 上架：建好各选项的奖池行；每次跳转：pool 同步状态副本，提交后推送（S4）
    MarketPoolView p = pool.getIfAvailable();
    if (p != null) {
      if (to == MarketStatus.LISTED && from == MarketStatus.DRAFT) {
        p.open(id, mapper.options(id).stream().map(o -> new MarketPoolView.OptionRef(o.id, o.publicNo, o.seq)).toList());
      }
      p.changed(id, to);
    }
    listeners.orderedStream().forEach(x -> x.onStatusChanged(id, from, to));
    if (to == MarketStatus.CANCELLED || to == MarketStatus.VOIDED) listeners.orderedStream().forEach(x -> x.onRefundRequired(id, to, cut));
    return true;
  }

  /** 费率、截止时间改了：推送一次新的奖池和赔率（状态不变） */
  private void poolChanged(long id, MarketStatus status) {
    MarketPoolView p = pool.getIfAvailable();
    if (p != null) p.changed(id, status);
  }

  // =====================================================================
  // 定时任务（MarketClockJob 调用，也给测试和端到端测试直接调用）
  // =====================================================================

  /**
   * 截止：{@code status IN (LISTED, PAUSED) AND close_at ≤ now} 的市场逐个处理，每个市场一个事务：
   * 锁市场行（排他，等进行中的下注提交）→ 定格奖池、数有效选项 → LISTED / PAUSED → CLOSED → 有效选项不足 2 个时 CLOSED → VOIDED（E3、E4）。
   * 按"条件已满足"扫描，停机期间到期的市场恢复后第一轮处理（NF-05）。
   *
   * @return 这一轮截止了几个市场
   */
  public int closeDue() {
    LocalDateTime now = now();
    int n = 0;
    for (long id : mapper.dueToClose(now, BATCH)) {
      try {
        if (Boolean.TRUE.equals(tx.execute(st -> closeOne(id, now)))) n++;
      } catch (RuntimeException e) {
        log.error("市场 {} 截止失败，下一轮重试", id, e);
      }
    }
    return n;
  }

  private boolean closeOne(long id, LocalDateTime now) {
    MarketRows.Market m = mapper.lockById(id);
    if (m == null || m.deletedAt != null) return false;
    MarketStatus s = status(m);
    if ((s != MarketStatus.LISTED && s != MarketStatus.PAUSED) || m.closeAt.isAfter(now)) return false;
    MarketPoolView p = pool.getIfAvailable();
    // 没有 pool 模块（S3）：还不能下注，有效选项为 0，按 E4（总奖池为 0）作废
    int active = p == null ? 0 : p.freezeAndCountActiveOptions(id);
    if (!move(id, s, MarketStatus.CLOSED, SYSTEM, null, null, now)) return false;
    if (active < 2) move(id, MarketStatus.CLOSED, MarketStatus.VOIDED, SYSTEM, VOID_NOT_ENOUGH_OPTIONS, null, now);
    return true;
  }

  /** 事件结束：{@code CLOSED AND event_end_at ≤ now} → PENDING_RESOLUTION（第 9 条边） */
  public int endDue() {
    LocalDateTime now = now();
    int n = 0;
    for (long id : mapper.dueToEnd(now, BATCH)) {
      try {
        Boolean ok = tx.execute(st -> {
          MarketRows.Market m = mapper.lockById(id);
          if (m == null || status(m) != MarketStatus.CLOSED || m.eventEndAt.isAfter(now)) return false;
          return move(id, MarketStatus.CLOSED, MarketStatus.PENDING_RESOLUTION, SYSTEM, null, null, now);
        });
        if (Boolean.TRUE.equals(ok)) n++;
      } catch (RuntimeException e) {
        log.error("市场 {} 进入待裁决失败，下一轮重试", id, e);
      }
    }
    return n;
  }

  // =====================================================================
  // 后台（MarketAdminApi）
  // =====================================================================

  @Override
  @Transactional(readOnly = true)
  public Page<Summary> list(String status, String category, Boolean submitted, String keyword, int page, int pageSize) {
    int p = Math.max(1, page);
    int size = Math.min(100, Math.max(1, pageSize));
    String st = null;
    if (status != null && !status.isBlank()) {
      MarketStatus s = MarketStatus.ofApi(status);
      if (s == null) throw new ValidationException(List.of(new ValidationException.Field("status", "invalid")));
      st = s.name();
    }
    String cat = null;
    if (category != null && !category.isBlank()) {
      MarketCategory c = MarketCategory.of(category);
      if (c == null) throw new ValidationException(List.of(new ValidationException.Field("category", "invalid")));
      cat = c.name();
    }
    String kw = keyword == null || keyword.isBlank() ? null : keyword.strip();
    Long no = kw != null && kw.matches("\\d{1,19}") ? parseLong(kw) : null;
    List<Summary> items = mapper.adminList(st, cat, submitted, kw, no, (p - 1) * size, size).stream().map(this::summary).toList();
    return new Page<>(items, mapper.adminCount(st, cat, submitted, kw, no), p, size);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<Detail> detail(long publicNo) {
    MarketRows.Market m = mapper.findByPublicNo(publicNo);
    return m == null ? Optional.empty() : Optional.of(detailOf(m));
  }

  @Override
  @Transactional
  public Detail createDraft(DraftInput in, long adminId) {
    Draft d = parse(in);
    LocalDateTime now = now();
    MarketRows.Market m = new MarketRows.Market();
    d.fill(m);
    m.publicNo = ids.next();
    m.feeRate = defaultFeeRate(d.category);
    m.createdBy = adminId;
    m.createdAt = now;
    m.updatedAt = now;
    mapper.insertMarket(m);
    insertOptions(m.id, d.options, now);
    return detailOf(mapper.findById(m.id));
  }

  @Override
  @Transactional
  public Detail updateDraft(long publicNo, DraftInput in, long adminId) {
    MarketRows.Market old = lockDraft(publicNo);
    Draft d = parse(in);
    LocalDateTime now = now();
    MarketRows.Market m = new MarketRows.Market();
    d.fill(m);
    m.id = old.id;
    m.updatedAt = now;
    if (mapper.updateDraft(m) != 1) throw notEditable();
    // 草稿换了品类、又没有人工改过费率：费率跟着换成新品类的默认值（人工改过的保留，费率只能经"改费率"变）
    if (!old.category.equals(m.category) && mapper.feeChanges(old.id).isEmpty()) mapper.updateFee(old.id, defaultFeeRate(d.category), now);
    mapper.deleteDraftOptions(old.id);
    insertOptions(old.id, d.options, now);
    return detailOf(mapper.findById(old.id));
  }

  @Override
  @Transactional
  public void deleteDraft(long publicNo) {
    MarketRows.Market m = lockDraft(publicNo);
    if (mapper.softDeleteDraft(m.id, now()) != 1) throw notEditable();
  }

  @Override
  @Transactional
  public Detail submit(long publicNo) {
    MarketRows.Market m = lockDraft(publicNo);
    List<ValidationException.Field> missing = missing(m, mapper.options(m.id));
    if (!missing.isEmpty()) throw new ValidationException(missing);
    mapper.setSubmitted(m.id, true, now());
    return detailOf(mapper.findById(m.id));
  }

  @Override
  @Transactional
  public Detail returnDraft(long publicNo) {
    MarketRows.Market m = lockDraft(publicNo);
    mapper.setSubmitted(m.id, false, now());
    return detailOf(mapper.findById(m.id));
  }

  @Override
  @Transactional
  public Detail publish(long publicNo, boolean checked, long adminId) {
    MarketRows.Market m = lock(publicNo);
    MarketStateMachine.check(status(m), MarketStatus.LISTED);
    LocalDateTime now = now();
    List<ValidationException.Field> errs = new ArrayList<>(missing(m, mapper.options(m.id)));
    // 第 4.1 节：勾选"已检查以上各项"才能上架（可判定、来源写清、边界情况、选项覆盖所有可能、不是"不做"的题目）
    if (!checked) errs.add(new ValidationException.Field("checked", "required"));
    if (!m.closeAt.isAfter(now)) errs.add(new ValidationException.Field("closeAt", "passed"));
    if (!errs.isEmpty()) throw new ValidationException(errs);
    if (!move(m.id, MarketStatus.DRAFT, MarketStatus.LISTED, String.valueOf(adminId), null, adminId, now)) throw stateChanged();
    return detailOf(mapper.findById(m.id));
  }

  @Override
  @Transactional
  public Detail pause(long publicNo, long adminId, String reason) {
    MarketRows.Market m = lock(publicNo);
    MarketStateMachine.check(status(m), MarketStatus.PAUSED);
    if (!move(m.id, status(m), MarketStatus.PAUSED, String.valueOf(adminId), reason, null, now())) throw stateChanged();
    return detailOf(mapper.findById(m.id));
  }

  @Override
  @Transactional
  public Detail resume(long publicNo, long adminId, String reason) {
    MarketRows.Market m = lock(publicNo);
    MarketStateMachine.check(status(m), MarketStatus.LISTED);
    LocalDateTime now = now();
    // 截止时间已过：不恢复，由截止任务进入"截止下注"（需求第 3.2 节第 2 条）
    if (!m.closeAt.isAfter(now)) throw new BizException("MARKET_CLOSED", "已过截止时间，不能恢复下注");
    if (!move(m.id, MarketStatus.PAUSED, MarketStatus.LISTED, String.valueOf(adminId), reason, null, now)) throw stateChanged();
    return detailOf(mapper.findById(m.id));
  }

  @Override
  @Transactional
  public Detail cancel(long publicNo, long adminId, String reason) {
    MarketRows.Market m = lock(publicNo);
    MarketStateMachine.check(status(m), MarketStatus.CANCELLED);
    LocalDateTime now = now();
    // 取消只能在截止前；截止时间已到（截止任务还没跑到）的按"截止后作废"处理（resolve:void，S7）
    if (!m.closeAt.isAfter(now)) throw new BizException("MARKET_CLOSED", "已过截止时间，不能取消，只能作废");
    if (!move(m.id, status(m), MarketStatus.CANCELLED, String.valueOf(adminId), reason, null, now)) throw stateChanged();
    return detailOf(mapper.findById(m.id));
  }

  @Override
  @Transactional
  public Detail clarify(long publicNo, I18nText text, long adminId) {
    MarketRows.Market m = lock(publicNo);
    I18nText t = (text == null ? I18nText.EMPTY : text).normalized();
    ValidationException.Collector errs = new ValidationException.Collector();
    requireText(errs, "text", t, TEXT_MAX);
    errs.throwIfAny();
    LocalDateTime now = now();
    MarketStatus s = status(m);
    if (s == MarketStatus.DRAFT) throw new BizException("MARKET_NOT_LISTED", "草稿直接修改规则，不用发补充说明");
    // E11：补充说明在截止前发；截止后发现歧义的由裁决员按通常含义提议、或作废
    if ((s != MarketStatus.LISTED && s != MarketStatus.PAUSED) || !m.closeAt.isAfter(now)) throw new BizException("MARKET_CLOSED", "已截止，不能再发补充说明");
    MarketRows.Clarification c = new MarketRows.Clarification();
    c.marketId = m.id;
    c.textEn = t.en();
    c.textZhCn = t.zhCN();
    c.textZhTw = t.zhTW();
    c.createdBy = adminId;
    c.createdAt = now;
    mapper.insertClarification(c);
    return detailOf(mapper.findById(m.id));
  }

  @Override
  @Transactional
  public Detail advanceClose(long publicNo, String closeAt, long adminId, String reason) {
    MarketRows.Market m = lock(publicNo);
    MarketStatus s = status(m);
    if (s == MarketStatus.DRAFT) throw new BizException("MARKET_NOT_LISTED", "草稿直接修改截止时间");
    if (s != MarketStatus.LISTED && s != MarketStatus.PAUSED) throw new BizException("MARKET_CLOSED", "已截止，不能再改截止时间");
    Instant t = MarketFormats.parseInstant(closeAt);
    ValidationException.Collector errs = new ValidationException.Collector();
    if (t == null) errs.add("closeAt", closeAt == null || closeAt.isBlank() ? "required" : "invalid");
    else {
      LocalDateTime c = ldt(t);
      // 只能提前、不能推后（需求第 3.2 节第 4 条）；仍要晚于开放下注时间（第 3.7 节第 3 条）
      if (!c.isBefore(m.closeAt)) errs.add("closeAt", "notEarlier");
      else if (!c.isAfter(m.openAt)) errs.add("closeAt", "notAfterOpen");
    }
    errs.throwIfAny();
    LocalDateTime newClose = ldt(t);
    if (mapper.advanceClose(m.id, newClose, now()) != 1) throw stateChanged();
    Instant oldClose = instant(m.closeAt);
    listeners.orderedStream().forEach(x -> x.onCloseAdvanced(m.id, oldClose, instant(newClose)));
    poolChanged(m.id, s);
    return detailOf(mapper.findById(m.id));
  }

  @Override
  @Transactional
  public FeeChange changeFee(long publicNo, String feeRatePercent, long adminId, String reason) {
    BigDecimal rate = MarketFormats.rateOf(feeRatePercent);
    if (rate == null) throw new ValidationException(List.of(new ValidationException.Field("feeRate", feeRatePercent == null || feeRatePercent.isBlank() ? "required" : "invalid")));
    MarketRows.Market m = lock(publicNo);
    MarketStatus s = status(m);
    // 结算开始后不能改（需求第 3.8 节第 3 条）：终点状态，或 S5 的结算已经开始算（settle_phase 不是 NONE）
    if (s.terminal() || !"NONE".equals(m.settlePhase)) throw new BizException("FEE_LOCKED", "结算已开始，不能再改费率");
    if (rate.compareTo(m.feeRate) == 0) throw new ValidationException(List.of(new ValidationException.Field("feeRate", "unchanged")));
    LocalDateTime now = now();
    if (mapper.updateFee(m.id, rate, now) != 1) throw stateChanged();
    // 预计赔率按新费率显示（第 3.8 节）：推送一次
    poolChanged(m.id, s);
    // 草稿阶段改的不通知（第 7 节 #22）；上架后改的，通知这个市场有有效下注的客户（S4 起由 pool 提供名单）
    boolean listed = s != MarketStatus.DRAFT;
    MarketRows.FeeChange c = new MarketRows.FeeChange();
    c.marketId = m.id;
    c.oldRate = m.feeRate;
    c.newRate = rate;
    c.reason = cut(reason == null ? "" : reason, 500);
    c.changedBy = adminId;
    c.changedAt = now;
    c.notified = listed;
    mapper.insertFeeChange(c);
    int notified = 0;
    if (listed) {
      MarketPoolView p = pool.getIfAvailable();
      List<Long> users = p == null ? List.of() : p.bettors(m.id);
      Map<String, String> params = new LinkedHashMap<>();
      params.put("market", m.titleZhCn);
      params.put("marketEn", m.titleEn);
      params.put("old", percent(m.feeRate) + "%");
      params.put("new", percent(rate) + "%");
      params.put("at", MarketFormats.noticeTime(now));
      for (Long uid : users) {
        notify.toUser(uid, "FEE_CHANGED", params);
        notified++;
      }
    }
    return new FeeChange(percent(m.feeRate), percent(rate), notified, detailOf(mapper.findById(m.id)));
  }

  // =====================================================================
  // 客户端（market/web/MarketController）
  // =====================================================================

  /**
   * 客户端列表。status：不传为全部（不含草稿）；可以是一个状态（小写），或一组：{@code active}（已上架、已暂停）、
   * {@code closed}（截止下注到争议中，等结果）、{@code ended}（已结算、已取消、已作废）。sort：{@code closeAt}（默认）/ {@code newest}。
   */
  @Transactional(readOnly = true)
  public ClientViews.Page<ClientViews.Card> clientList(String status, String category, String sort, int page, int pageSize) {
    ValidationException.Collector errs = new ValidationException.Collector();
    Set<MarketStatus> statuses = clientStatuses(status);
    if (statuses == null) errs.add("status", "invalid");
    MarketCategory cat = category == null || category.isBlank() ? null : MarketCategory.of(category);
    if (category != null && !category.isBlank() && cat == null) errs.add("category", "invalid");
    String srt = sort == null || sort.isBlank() ? "closeAt" : sort.strip();
    if (!srt.equals("closeAt") && !srt.equals("newest")) errs.add("sort", "invalid");
    errs.throwIfAny();
    int p = Math.max(1, page);
    int size = Math.min(50, Math.max(1, pageSize));
    List<String> st = statuses.stream().map(Enum::name).toList();
    String c = cat == null ? null : cat.name();
    List<MarketRows.Market> rows = mapper.clientList(st, c, srt, (p - 1) * size, size);
    Map<Long, List<MarketRows.Option>> opts = rows.isEmpty() ? Map.of()
        : mapper.optionsOf(rows.stream().map(r -> r.id).toList()).stream().collect(Collectors.groupingBy(o -> o.marketId));
    Map<Long, Object> pools = clientPools(rows, opts, null);
    List<ClientViews.Card> items = rows.stream().map(m -> new ClientViews.Card(String.valueOf(m.publicNo), m.category, title(m), status(m).api(), iso(m.openAt),
        iso(m.closeAt), iso(m.eventStartAt), iso(m.eventEndAt), percent(m.feeRate),
        opts.getOrDefault(m.id, List.of()).stream().map(o -> new ClientViews.OptionCard(String.valueOf(o.publicNo), o.seq, label(o))).toList(),
        pools.get(m.id))).toList();
    return new ClientViews.Page<>(items, mapper.clientCount(st, c), p, size);
  }

  /** 客户端详情；草稿、删除的、不存在的都是空（404）。userId：当前客户，详情的奖池里带上他的持仓（S4） */
  @Transactional(readOnly = true)
  public Optional<ClientViews.Detail> clientDetail(long publicNo, Long userId) {
    MarketRows.Market m = mapper.findByPublicNo(publicNo);
    if (m == null || !VISIBLE.contains(status(m))) return Optional.empty();
    List<MarketRows.Option> optionRows = mapper.options(m.id);
    Object poolView = clientPools(List.of(m), Map.of(m.id, optionRows), userId).get(m.id);
    List<ClientViews.OptionDetail> options = optionRows.stream()
        .map(o -> new ClientViews.OptionDetail(String.valueOf(o.publicNo), o.seq, label(o), winCondition(o))).toList();
    List<ClientViews.Clarification> cl = mapper.clarifications(m.id).stream()
        .map(c -> new ClientViews.Clarification(new I18nText(c.textEn, c.textZhCn, c.textZhTw), iso(c.createdAt))).toList();
    List<ClientViews.FeeChange> fees = mapper.feeChanges(m.id).stream().map(f -> new ClientViews.FeeChange(percent(f.oldRate), percent(f.newRate), iso(f.changedAt))).toList();
    // 裁决信息（resolve 模块提供）：S6 已结算的自动裁决（开始价、结束价、来源，需求第 4.3 节、AC-27）；S7 平台裁决的公示、争议、结论
    // （结果、依据、裁决人编号、争议截止时间、争议结论，需求第 4.4 节、第 5.2 节 P2、AC-34），公示中、争议中、已结算、已作废时有
    MarketResolutionView rv = resolution.getIfAvailable();
    Object resolved = rv != null && RESOLUTION_VISIBLE.contains(status(m)) ? rv.clientResolution(m.id, status(m), userId) : null;
    return Optional.of(new ClientViews.Detail(String.valueOf(m.publicNo), m.category, title(m), rules(m), status(m).api(), m.resolutionMode, m.sourcePrimary,
        m.sourceBackup, iso(m.openAt), iso(m.closeAt), iso(m.eventStartAt), iso(m.eventEndAt), percent(m.feeRate), money(m.userCap), m.disputeWindowMin,
        m.coverPath, m.cancelReason, m.voidReason, winningOptionNo(m, optionRows), SettlePhase.PAYING.name().equals(m.settlePhase), options, cl, fees,
        poolView, resolved));
  }

  /** 已结算的市场才公开结果（S5 超管填的结果；公示中的拟定结果在 S7 另给） */
  private static String winningOptionNo(MarketRows.Market m, List<MarketRows.Option> options) {
    if (m.winningOptionId == null || status(m) != MarketStatus.SETTLED) return null;
    return options.stream().filter(o -> o.id.equals(m.winningOptionId)).findFirst().map(o -> String.valueOf(o.publicNo)).orElse(null);
  }

  /** 客户端接口里的奖池（pool 模块提供；没有 pool 模块时没有这个字段的值） */
  private Map<Long, Object> clientPools(List<MarketRows.Market> rows, Map<Long, List<MarketRows.Option>> opts, Long userId) {
    MarketPoolView p = pool.getIfAvailable();
    if (p == null || rows.isEmpty()) return Map.of();
    List<MarketPoolView.PoolContext> ctx = rows.stream().map(m -> new MarketPoolView.PoolContext(m.id, status(m), m.feeRate, m.userCap,
        opts.getOrDefault(m.id, List.of()).stream().map(o -> new MarketPoolView.OptionRef(o.id, o.publicNo, o.seq)).toList())).toList();
    return p.clientPools(ctx, userId);
  }

  static Set<MarketStatus> clientStatuses(String status) {
    if (status == null || status.isBlank() || status.strip().equals("all")) return VISIBLE;
    return switch (status.strip()) {
      case "active" -> EnumSet.of(MarketStatus.LISTED, MarketStatus.PAUSED);
      case "closed" -> EnumSet.of(MarketStatus.CLOSED, MarketStatus.PENDING_RESOLUTION, MarketStatus.PUBLICIZING, MarketStatus.DISPUTED);
      case "ended" -> EnumSet.of(MarketStatus.SETTLED, MarketStatus.CANCELLED, MarketStatus.VOIDED);
      default -> {
        MarketStatus s = MarketStatus.ofApi(status);
        yield s == null || s == MarketStatus.DRAFT ? null : EnumSet.of(s);
      }
    };
  }

  // =====================================================================
  // 草稿的校验
  // =====================================================================

  /** 解析、校验过的草稿输入 */
  private record Draft(MarketCategory category, I18nText title, I18nText rules, ResolutionMode mode, String sourcePrimary, String sourceBackup,
      String sourceRef, LocalDateTime openAt, LocalDateTime closeAt, LocalDateTime eventStartAt, LocalDateTime eventEndAt, BigDecimal userCap,
      Integer disputeWindow, String coverPath, List<OptionInput> options) {

    void fill(MarketRows.Market m) {
      m.category = category.name();
      m.titleEn = title.en();
      m.titleZhCn = title.zhCN();
      m.titleZhTw = title.zhTW();
      m.rulesEn = rules.en();
      m.rulesZhCn = rules.zhCN();
      m.rulesZhTw = rules.zhTW();
      m.resolutionMode = mode == null ? null : mode.name();
      m.sourcePrimary = sourcePrimary;
      m.sourceBackup = sourceBackup;
      m.sourceRef = sourceRef;
      m.openAt = openAt;
      m.closeAt = closeAt;
      m.eventStartAt = eventStartAt;
      m.eventEndAt = eventEndAt;
      m.userCap = userCap;
      m.disputeWindowMin = disputeWindow;
      m.coverPath = coverPath;
    }
  }

  /**
   * 保存草稿时的检查（AC-11）：品类；选项 2～10 个；四个时间都有、格式正确、先后正确（开放 &lt; 截止 ≤ 事件开始 &lt; 事件结束，第 3.7 节第 3 条）；
   * 单客户上限 &gt; 0、最多 2 位小数、不超过品类默认值（只能调低）；争议窗口 1～10080 分钟；长度上限。一次列出全部错误（C56）。
   */
  private Draft parse(DraftInput in) {
    if (in == null) throw new ValidationException(List.of(new ValidationException.Field("category", "required")));
    ValidationException.Collector errs = new ValidationException.Collector();
    MarketCategory category = MarketCategory.of(in.category());
    if (category == null) errs.add("category", in.category() == null || in.category().isBlank() ? "required" : "invalid");
    I18nText title = (in.title() == null ? I18nText.EMPTY : in.title()).normalized();
    I18nText rules = (in.rules() == null ? I18nText.EMPTY : in.rules()).normalized();
    maxLen(errs, "title", title, 300);
    maxLen(errs, "rules", rules, RULES_MAX);
    ResolutionMode mode = null;
    if (in.resolutionMode() != null && !in.resolutionMode().isBlank()) {
      mode = ResolutionMode.of(in.resolutionMode());
      if (mode == null) errs.add("resolutionMode", "invalid");
    }
    String primary = clean(in.sourcePrimary());
    String backup = clean(in.sourceBackup());
    if (primary.length() > 300) errs.add("sourcePrimary", "tooLong");
    if (backup.length() > 300) errs.add("sourceBackup", "tooLong");
    String sourceRef = null;
    if (in.sourceRef() != null && !in.sourceRef().isEmpty()) {
      sourceRef = json.writeValueAsString(in.sourceRef());
      if (sourceRef.length() > 10_000) errs.add("sourceRef", "tooLong");
    }
    LocalDateTime open = time(errs, "openAt", in.openAt());
    LocalDateTime close = time(errs, "closeAt", in.closeAt());
    LocalDateTime start = time(errs, "eventStartAt", in.eventStartAt());
    LocalDateTime end = time(errs, "eventEndAt", in.eventEndAt());
    if (open != null && close != null && !open.isBefore(close)) errs.add("closeAt", "notAfterOpen");
    if (close != null && start != null && close.isAfter(start)) errs.add("eventStartAt", "beforeClose");
    if (start != null && end != null && !start.isBefore(end)) errs.add("eventEndAt", "notAfterStart");
    BigDecimal cap = null;
    if (category != null) {
      BigDecimal def = config.decimal(category.userCapKey, null);
      if (in.userCap() == null || in.userCap().isBlank()) {
        cap = def;
        if (cap == null) errs.add("userCap", "required");
      } else {
        try {
          cap = new BigDecimal(in.userCap().strip());
          if (cap.signum() <= 0 || cap.stripTrailingZeros().scale() > 2) errs.add("userCap", "invalid");
          else if (def != null && cap.compareTo(def) > 0) errs.add("userCap", "aboveDefault");
        } catch (NumberFormatException e) {
          errs.add("userCap", "invalid");
        }
      }
    }
    Integer window = in.disputeWindowMinutes();
    if (window != null && (window < 1 || window > 10_080)) errs.add("disputeWindowMinutes", "invalid");
    if (window == null && mode == ResolutionMode.PLATFORM && category != null && category.disputeWindowKey != null) {
      window = config.integer(category.disputeWindowKey, 0);
      if (window <= 0) window = null;
    }
    String cover = clean(in.coverPath());
    if (cover.length() > 300) errs.add("coverPath", "tooLong");
    List<OptionInput> options = in.options() == null ? List.of() : in.options();
    if (options.size() < 2) errs.add("options", "min2");
    if (options.size() > 10) errs.add("options", "max10");
    List<OptionInput> norm = new ArrayList<>();
    for (int i = 0; i < options.size(); i++) {
      OptionInput o = options.get(i);
      I18nText label = (o == null || o.label() == null ? I18nText.EMPTY : o.label()).normalized();
      I18nText win = (o == null || o.winCondition() == null ? I18nText.EMPTY : o.winCondition()).normalized();
      maxLen(errs, "options[" + i + "].label", label, 200);
      maxLen(errs, "options[" + i + "].winCondition", win, 1000);
      norm.add(new OptionInput(label, win));
    }
    errs.throwIfAny();
    return new Draft(category, title, rules, mode, primary, backup, sourceRef, open, close, start, end, cap, window, cover.isEmpty() ? null : cover, norm);
  }

  /**
   * 提交上架、上架前的检查（第 4.1 节"每个市场必须填齐"，AC-11）：三种语言的题目、规则（判定条件、取消作废条件、数据修正）、
   * 每个选项三种语言的文字和判定条件、裁决方式、首选和备用来源、平台裁决的争议窗口。返回缺的项（字段名 + required），一次列出全部。
   */
  List<ValidationException.Field> missing(MarketRows.Market m, List<MarketRows.Option> options) {
    List<ValidationException.Field> out = new ArrayList<>();
    requireLangs(out, "title", title(m));
    requireLangs(out, "rules", rules(m));
    if (m.resolutionMode == null) out.add(new ValidationException.Field("resolutionMode", "required"));
    if (m.sourcePrimary == null || m.sourcePrimary.isBlank()) out.add(new ValidationException.Field("sourcePrimary", "required"));
    if (m.sourceBackup == null || m.sourceBackup.isBlank()) out.add(new ValidationException.Field("sourceBackup", "required"));
    if ("PLATFORM".equals(m.resolutionMode) && (m.disputeWindowMin == null || m.disputeWindowMin <= 0)) {
      out.add(new ValidationException.Field("disputeWindowMinutes", "required"));
    }
    if (options.size() < 2) out.add(new ValidationException.Field("options", "min2"));
    for (int i = 0; i < options.size(); i++) {
      requireLangs(out, "options[" + i + "].label", label(options.get(i)));
      requireLangs(out, "options[" + i + "].winCondition", winCondition(options.get(i)));
    }
    return out;
  }

  private static void requireLangs(List<ValidationException.Field> out, String field, I18nText t) {
    if (t.en() == null || t.en().isBlank()) out.add(new ValidationException.Field(field + ".en", "required"));
    if (t.zhCN() == null || t.zhCN().isBlank()) out.add(new ValidationException.Field(field + ".zhCN", "required"));
    if (t.zhTW() == null || t.zhTW().isBlank()) out.add(new ValidationException.Field(field + ".zhTW", "required"));
  }

  private static void requireText(ValidationException.Collector errs, String field, I18nText t, int max) {
    if (t.en().isBlank()) errs.add(field + ".en", "required");
    if (t.zhCN().isBlank()) errs.add(field + ".zhCN", "required");
    if (t.zhTW().isBlank()) errs.add(field + ".zhTW", "required");
    maxLen(errs, field, t, max);
  }

  private static void maxLen(ValidationException.Collector errs, String field, I18nText t, int max) {
    if (t.en().length() > max) errs.add(field + ".en", "tooLong");
    if (t.zhCN().length() > max) errs.add(field + ".zhCN", "tooLong");
    if (t.zhTW().length() > max) errs.add(field + ".zhTW", "tooLong");
  }

  private static LocalDateTime time(ValidationException.Collector errs, String field, String v) {
    if (v == null || v.isBlank()) {
      errs.add(field, "required");
      return null;
    }
    Instant i = MarketFormats.parseInstant(v);
    if (i == null) {
      errs.add(field, "invalid");
      return null;
    }
    return ldt(i);
  }

  private void insertOptions(long marketId, List<OptionInput> options, LocalDateTime now) {
    for (int i = 0; i < options.size(); i++) {
      OptionInput in = options.get(i);
      MarketRows.Option o = new MarketRows.Option();
      o.publicNo = ids.next();
      o.marketId = marketId;
      o.seq = i + 1;
      o.labelEn = in.label().en();
      o.labelZhCn = in.label().zhCN();
      o.labelZhTw = in.label().zhTW();
      o.winCondition = json.writeValueAsString(in.winCondition());
      o.createdAt = now;
      mapper.insertOption(o);
    }
  }

  /** 品类默认费率（参数设置页的百分比）→ 数据库费率；读不到或不合法时用 5%（需求 RQ-4 的默认值） */
  private BigDecimal defaultFeeRate(MarketCategory c) {
    BigDecimal p = config.decimal(c.feeKey, null);
    BigDecimal r = p == null ? null : MarketFormats.rateOf(p.toPlainString());
    if (r == null) {
      log.warn("品类 {} 的默认费率读不到或不合法，用 5%", c);
      return new BigDecimal("0.0500");
    }
    return r;
  }

  // =====================================================================
  // 自动生成（S6，market/autogen 调用）
  // =====================================================================

  /** 状态历史里自动生成上架的 actor */
  public static final String AUTOGEN = "AUTOGEN";

  /**
   * 按自动生成模板渲染好的一个市场（加密涨跌，需求第 4.2 节）。费率不在这里：按品类默认值带出（第 3.8 节第 2 条）。
   *
   * @param userCap 为空用品类默认值
   */
  public record Generated(long autogenId, MarketCategory category, I18nText title, I18nText rules, String sourcePrimary, String sourceBackup,
      Map<String, Object> sourceRef, Instant openAt, Instant closeAt, Instant eventStartAt, Instant eventEndAt, BigDecimal userCap,
      List<OptionInput> options) {}

  /**
   * 渲染出来的市场缺哪些必填项（第 4.1 节，和上架检查同一个清单）、时间先后对不对。启用模板时检查一次（空表示齐全）。
   */
  public List<ValidationException.Field> generatedMissing(Generated g) {
    MarketRows.Market m = generatedRow(g);
    List<MarketRows.Option> opts = new ArrayList<>();
    for (OptionInput o : g.options()) {
      MarketRows.Option r = new MarketRows.Option();
      r.labelEn = o.label().en();
      r.labelZhCn = o.label().zhCN();
      r.labelZhTw = o.label().zhTW();
      r.winCondition = json.writeValueAsString(o.winCondition());
      opts.add(r);
    }
    List<ValidationException.Field> out = new ArrayList<>(missing(m, opts));
    if (opts.size() > 10) out.add(new ValidationException.Field("options", "max10"));
    if (!m.openAt.isBefore(m.closeAt)) out.add(new ValidationException.Field("closeAt", "notAfterOpen"));
    if (m.closeAt.isAfter(m.eventStartAt)) out.add(new ValidationException.Field("eventStartAt", "beforeClose"));
    if (!m.eventStartAt.isBefore(m.eventEndAt)) out.add(new ValidationException.Field("eventEndAt", "notAfterStart"));
    return out;
  }

  /**
   * 生成一个市场并<b>直接上架</b>（第 4.2 节"按模板生成的市场直接上架"；第 1 条边 DRAFT → LISTED，actor {@code AUTOGEN}）。必须在调用方的事务里。
   * 同一模板同一窗口只有一个（唯一键 {@code uk_market_autogen_window}，重复插入抛 {@link org.springframework.dao.DuplicateKeyException}，调用方当作"已生成"）。
   * 必填项不齐、时间不对、截止时间已过时抛 {@link ValidationException}，什么都不写。
   *
   * @return 新市场的对外编号
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public long createGenerated(Generated g) {
    LocalDateTime now = now();
    List<ValidationException.Field> errs = new ArrayList<>(generatedMissing(g));
    if (!ldt(g.closeAt()).isAfter(now)) errs.add(new ValidationException.Field("closeAt", "passed"));
    if (!errs.isEmpty()) throw new ValidationException(errs);
    MarketRows.Market m = generatedRow(g);
    m.publicNo = ids.next();
    m.createdAt = now;
    m.updatedAt = now;
    mapper.insertMarket(m);
    insertOptions(m.id, g.options().stream().map(o -> new OptionInput(o.label().normalized(), o.winCondition().normalized())).toList(), now);
    if (!move(m.id, MarketStatus.DRAFT, MarketStatus.LISTED, AUTOGEN, null, null, now)) throw stateChanged();
    return m.publicNo;
  }

  /** 自动生成模板在 [from, to]（窗口开始时间）里已经生成的市场：窗口开始时间 → 对外编号 */
  @Transactional(readOnly = true)
  public Map<Instant, Long> generatedOf(long autogenId, Instant from, Instant to) {
    Map<Instant, Long> out = new LinkedHashMap<>();
    for (MarketRows.Market m : mapper.generated(autogenId, ldt(from), ldt(to))) out.put(instant(m.eventStartAt), m.publicNo);
    return out;
  }

  private MarketRows.Market generatedRow(Generated g) {
    MarketRows.Market m = new MarketRows.Market();
    m.category = g.category().name();
    I18nText t = g.title().normalized();
    I18nText r = g.rules().normalized();
    m.titleEn = t.en();
    m.titleZhCn = t.zhCN();
    m.titleZhTw = t.zhTW();
    m.rulesEn = r.en();
    m.rulesZhCn = r.zhCN();
    m.rulesZhTw = r.zhTW();
    m.resolutionMode = ResolutionMode.AUTO.name();
    m.sourcePrimary = clean(g.sourcePrimary());
    m.sourceBackup = clean(g.sourceBackup());
    m.sourceRef = g.sourceRef() == null ? null : json.writeValueAsString(g.sourceRef());
    m.openAt = ldt(g.openAt());
    m.closeAt = ldt(g.closeAt());
    m.eventStartAt = ldt(g.eventStartAt());
    m.eventEndAt = ldt(g.eventEndAt());
    BigDecimal def = config.decimal(g.category().userCapKey, null);
    m.userCap = g.userCap() != null ? g.userCap() : def;
    m.feeRate = defaultFeeRate(g.category());
    m.autogenId = g.autogenId();
    return m;
  }

  // =====================================================================
  // 小工具
  // =====================================================================

  /**
   * 按对外编号锁市场行（排他）。<b>事务里的第一次读就是这个加锁读</b>（S5 改）：market 的事务是可重复读，快照建在第一次普通读时；
   * 原来先普通读再加锁，快照停在加锁之前，在这之间提交的下注同一事务里读不到（取消时的退款单、提前截止的退回、#22 的名单会漏掉它）。
   * 现在先加锁，之后的普通读才建快照，看得到锁之前提交的全部下注。
   */
  private MarketRows.Market lock(long publicNo) {
    MarketRows.Market m = mapper.lockByPublicNo(publicNo);
    if (m == null || m.deletedAt != null) throw new BizException("NOT_FOUND", "市场不存在");
    return m;
  }

  private MarketRows.Market lockDraft(long publicNo) {
    MarketRows.Market m = lock(publicNo);
    if (status(m) != MarketStatus.DRAFT) throw notEditable();
    return m;
  }

  /** 上架后题目、选项、时间、裁决来源都不能改（AC-12） */
  private static BizException notEditable() {
    return new BizException("MARKET_NOT_EDITABLE", "市场已上架，题目、选项、时间、裁决来源不能再改；只能发补充说明、提前截止或改费率");
  }

  private static BizException stateChanged() {
    return new BizException("MARKET_STATE_CHANGED", "市场状态刚被别人改过，请刷新后再操作");
  }

  private static MarketStatus status(MarketRows.Market m) {
    return MarketStatus.valueOf(m.status);
  }

  private static I18nText title(MarketRows.Market m) {
    return new I18nText(m.titleEn, m.titleZhCn, m.titleZhTw);
  }

  private static I18nText rules(MarketRows.Market m) {
    return new I18nText(m.rulesEn, m.rulesZhCn, m.rulesZhTw);
  }

  private static I18nText label(MarketRows.Option o) {
    return new I18nText(o.labelEn, o.labelZhCn, o.labelZhTw);
  }

  private I18nText winCondition(MarketRows.Option o) {
    if (o.winCondition == null || o.winCondition.isBlank()) return I18nText.EMPTY;
    I18nText t = json.readValue(o.winCondition, I18nText.class);
    return t == null ? I18nText.EMPTY : t.normalized();
  }

  private Map<String, Object> sourceRef(MarketRows.Market m) {
    if (m.sourceRef == null || m.sourceRef.isBlank()) return null;
    return json.readValue(m.sourceRef, new TypeReference<Map<String, Object>>() {});
  }

  private static String clean(String s) {
    return s == null ? "" : s.strip();
  }

  private static String cut(String s, int n) {
    return s.length() <= n ? s : s.substring(0, n);
  }

  private static Long parseLong(String s) {
    try {
      return Long.parseLong(s);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private Summary summary(MarketRows.Market m) {
    return new Summary(String.valueOf(m.publicNo), m.category, title(m), status(m).api(), m.submittedForListing, iso(m.openAt), iso(m.closeAt), iso(m.eventStartAt),
        iso(m.eventEndAt), percent(m.feeRate), iso(m.createdAt), iso(m.updatedAt));
  }

  private Detail detailOf(MarketRows.Market m) {
    List<MarketRows.Option> opts = mapper.options(m.id);
    List<OptionView> options = opts.stream().map(o -> new OptionView(String.valueOf(o.publicNo), o.seq, label(o), winCondition(o))).toList();
    List<ValidationException.Field> missing = status(m) == MarketStatus.DRAFT ? missing(m, opts) : List.of();
    List<StatusLogView> logs = mapper.statusLog(m.id).stream()
        .map(l -> new StatusLogView(MarketStatus.valueOf(l.fromStatus).api(), MarketStatus.valueOf(l.toStatus).api(), l.actor, l.reason, iso(l.createdAt))).toList();
    List<FeeChangeView> fees = mapper.feeChanges(m.id).stream()
        .map(f -> new FeeChangeView(percent(f.oldRate), percent(f.newRate), f.reason, f.changedBy, iso(f.changedAt), f.notified)).toList();
    List<ClarificationView> cl = mapper.clarifications(m.id).stream()
        .map(c -> new ClarificationView(new I18nText(c.textEn, c.textZhCn, c.textZhTw), c.createdBy, iso(c.createdAt))).toList();
    return new Detail(String.valueOf(m.publicNo), m.category, title(m), rules(m), status(m).api(), m.submittedForListing, m.settlePhase, m.resolutionMode,
        m.sourcePrimary, m.sourceBackup, sourceRef(m), iso(m.openAt), iso(m.closeAt), iso(m.eventStartAt), iso(m.eventEndAt), percent(m.feeRate), money(m.userCap),
        m.disputeWindowMin, m.coverPath, m.cancelReason, m.voidReason, m.createdBy, m.listedBy, m.version, iso(m.createdAt), iso(m.updatedAt), options, missing,
        logs, fees, cl);
  }
}
