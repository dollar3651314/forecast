package com.forecast.app.resolve.platform;

import com.forecast.app.common.error.BizException;
import com.forecast.app.common.error.ValidationException;
import com.forecast.app.common.id.Snowflake;
import com.forecast.app.common.storage.ObjectStorage;
import com.forecast.app.market.api.I18nText;
import com.forecast.app.market.api.MarketApi;
import com.forecast.app.market.api.MarketStatus;
import com.forecast.app.market.api.SettlePhase;
import com.forecast.app.notification.api.NotifyApi;
import com.forecast.app.notification.api.StaffAlertApi;
import com.forecast.app.pool.api.PoolApi;
import com.forecast.app.resolve.api.ResolveDeskApi;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 平台裁决（forecast S7；需求说明书第 3.2、3.6、4.4、7 节，AC-30～AC-36；技术方案/v1-架构方案.md 第 3.3、5 节）。实现 {@link ResolveDeskApi}。
 *
 * <h2>流程</h2>
 * 事件结束 → 待裁决（{@link CaseBook#open} 建进度）→ 裁决员<b>提议</b>（结果 + 依据链接 + 截图 + 理由；或提议作废）→ 有 {@code resolve:confirm} 的人<b>确认</b>
 * （结果：进入公示中、争议窗口开始、通知 #10；作废：已作废、全额退款）或<b>退回</b>；也可以不等提议<b>直接确认</b>。公示期满没有争议 →
 * {@link PublicityService} 交给结算。窗口内有下注的客户可以<b>提交争议</b>（每人一次）→ 争议中 → 有 {@code dispute:handle} 的人<b>处理</b>：维持 / 改判后直接结算，
 * 无法判定作废。E6 延期、E7 腰斩：标记<b>等待</b>，等待期间不能提议，48 小时内解除后照常提议，到时间没解除系统作废。
 *
 * <h2>并发</h2>
 * 每个写操作一个事务（READ COMMITTED）：先锁市场行（{@link MarketApi#lockPlatform}，和截止任务、结算、状态跳转串行）→ 再锁进度行 → 争议行 → 结算自己的锁。
 * 提交争议和公示期满的结算在市场行锁上排队：结算要求市场"正好在公示中"（{@code ResultHandler.settle(…, PUBLICIZING)}），争议要求"结算还没开始"，所以两者只会有一个生效。
 * 同一客户重复提交争议靠唯一键 (market_id, user_id) 兜底。状态跳转都经 {@link MarketApi}（带旧状态的条件更新）。
 */
@Service
public class ResolveDesk implements ResolveDeskApi {

  private static final Logger log = LoggerFactory.getLogger(ResolveDesk.class);

  public static final String PROPOSE = "PROPOSE";
  public static final String RETURN = "RETURN";
  public static final String CONFIRM = "CONFIRM";
  public static final String VOID = "VOID";
  public static final String HOLD = "HOLD";
  public static final String RELEASE = "RELEASE";
  public static final String PUBLICITY_END = "PUBLICITY_END";
  public static final String DISPUTE_UPHOLD = "DISPUTE_UPHOLD";
  public static final String DISPUTE_OVERTURN = "DISPUTE_OVERTURN";
  public static final String DISPUTE_VOID = "DISPUTE_VOID";
  /** 状态历史、裁决记录里系统操作的 actor */
  public static final String SYSTEM = "SYSTEM";
  /** 客户提交争议让市场进入争议中：状态历史里的 actor（不写客户编号，后台从争议列表看是谁） */
  public static final String CUSTOMER = "CUSTOMER";

  static final int NOTE_MAX = 1000;
  static final int URL_MAX = 500;
  static final int MAX_URLS = 5;
  static final int MAX_ATTACHMENTS = 5;
  static final int MAX_BYTES = 2 * 1024 * 1024;
  static final Set<MarketStatus> VOIDABLE = EnumSet.of(MarketStatus.PENDING_RESOLUTION, MarketStatus.PUBLICIZING, MarketStatus.DISPUTED);
  static final DateTimeFormatter NOTICE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'");

  private final PlatformMapper mapper;
  private final MarketApi markets;
  private final PoolApi pool;
  private final NotifyApi notify;
  private final StaffAlertApi staff;
  private final ObjectStorage storage;
  private final PlatformParams params;
  private final PublicityService publicity;
  private final Snowflake ids;
  private final Clock clock;
  private final ObjectMapper json;
  private final TransactionTemplate tx;

  ResolveDesk(PlatformMapper mapper, MarketApi markets, PoolApi pool, NotifyApi notify, StaffAlertApi staff, ObjectStorage storage, PlatformParams params,
      PublicityService publicity, Snowflake ids, Clock clock, ObjectMapper json, PlatformTransactionManager tm) {
    this.mapper = mapper;
    this.markets = markets;
    this.pool = pool;
    this.notify = notify;
    this.staff = staff;
    this.storage = storage;
    this.params = params;
    this.publicity = publicity;
    this.ids = ids;
    this.clock = clock;
    this.json = json;
    this.tx = new TransactionTemplate(tm);
    this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  LocalDateTime now() {
    return LocalDateTime.now(clock.withZone(ZoneOffset.UTC)).truncatedTo(ChronoUnit.MILLIS);
  }

  // =====================================================================
  // 裁决台：查看
  // =====================================================================

  @Override
  public Page<CaseItem> cases(String tab, int page, int pageSize) {
    String t = tab == null || tab.isBlank() || tab.strip().equalsIgnoreCase("all") ? null : tab.strip().toUpperCase(Locale.ROOT);
    if (t != null && !Set.of("AWAITING_PROPOSAL", "AWAITING_CONFIRM", "AUTO_FAILED", "ON_HOLD", "OVERDUE", "PUBLICIZING", "DISPUTED").contains(t)) {
      throw new ValidationException(List.of(new ValidationException.Field("tab", "invalid")));
    }
    int p = Math.max(1, page);
    int size = Math.min(100, Math.max(1, pageSize));
    LocalDateTime now = now();
    List<PlatformRows.Case> rows = mapper.listCases(t, now, (p - 1) * size, size);
    List<Long> marketIds = rows.stream().map(c -> c.marketId).toList();
    Map<Long, MarketApi.MarketRef> refs = markets.refs(marketIds);
    Map<Long, Integer> open = openCounts(marketIds);
    List<Long> proposalIds = rows.stream().map(c -> c.proposalId).filter(x -> x != null).toList();
    Map<Long, PlatformRows.Record> proposals = new LinkedHashMap<>();
    if (!proposalIds.isEmpty()) for (PlatformRows.Record r : mapper.recordsByIds(proposalIds)) proposals.put(r.id, r);
    List<Long> optionIds = new ArrayList<>();
    for (PlatformRows.Case c : rows) if (c.resultOptionId != null) optionIds.add(c.resultOptionId);
    for (PlatformRows.Record r : proposals.values()) if (r.optionId != null) optionIds.add(r.optionId);
    Map<Long, MarketApi.OptionRef> opts = markets.optionsById(optionIds);
    List<CaseItem> items = new ArrayList<>();
    for (PlatformRows.Case c : rows) {
      MarketApi.MarketRef m = refs.get(c.marketId);
      if (m == null) continue;
      items.add(item(c, m.publicNo(), m.category().name(), m.title(), m.status(), iso(m.eventEndAt()), open.getOrDefault(c.marketId, 0),
          c.proposalId == null ? null : proposals.get(c.proposalId), opts, now));
    }
    return new Page<>(items, mapper.countCases(t, now), p, size);
  }

  private Map<Long, Integer> openCounts(List<Long> marketIds) {
    Map<Long, Integer> out = new LinkedHashMap<>();
    if (marketIds.isEmpty()) return out;
    for (PlatformMapper.OpenCount oc : mapper.openCounts(marketIds)) out.put(oc.marketId, oc.n);
    return out;
  }

  private CaseItem item(PlatformRows.Case c, long marketNo, String category, I18nText title, MarketStatus status, String eventEndAt, int openDisputes,
      PlatformRows.Record proposal, Map<Long, MarketApi.OptionRef> opts, LocalDateTime now) {
    boolean proposeOverdue = "OPEN".equals(c.stage) && c.proposalId == null && c.holdKind == null && !c.proposeDueAt.isAfter(now);
    boolean confirmOverdue = "OPEN".equals(c.stage) && c.proposalId != null && c.confirmDueAt != null && !c.confirmDueAt.isAfter(now);
    boolean disputeOverdue = "DISPUTED".equals(c.stage) && c.decision == null && c.disputeDueAt != null && !c.disputeDueAt.isAfter(now);
    Proposal p = proposal == null ? null : proposal(proposal, c, opts, confirmOverdue);
    return new CaseItem(String.valueOf(marketNo), category, title, status.api(), lower(c.origin), lower(c.stage), lower(c.holdKind), iso(c.holdUntil),
        iso(c.proposeDueAt), proposeOverdue, p, optionNo(opts, c.resultOptionId), iso(c.disputeDeadlineAt), openDisputes, iso(c.disputeDueAt), disputeOverdue,
        proposeOverdue || confirmOverdue || disputeOverdue, eventEndAt, iso(c.updatedAt));
  }

  private Proposal proposal(PlatformRows.Record r, PlatformRows.Case c, Map<Long, MarketApi.OptionRef> opts, boolean confirmOverdue) {
    Map<String, Object> d = detailOf(r);
    return new Proposal(String.valueOf(r.id), String.valueOf(d.getOrDefault("outcome", "result")), optionNo(opts, r.optionId), (String) d.get("basis"),
        iso(r.actualStartAt), list(r.evidenceUrls), list(r.attachments), r.note, r.actor, iso(r.createdAt), c == null ? null : iso(c.confirmDueAt), confirmOverdue);
  }

  @Override
  public Optional<CaseDetail> detail(long marketNo) {
    Optional<MarketApi.MarketRef> ref = markets.find(marketNo);
    if (ref.isEmpty() || ref.get().status() == MarketStatus.DRAFT) return Optional.empty();
    long id = ref.get().marketId();
    PlatformRows.Case c = mapper.findCase(id);
    if (c == null) return Optional.empty();
    return Optional.of(detailOf(id, c));
  }

  private CaseDetail detailOf(long marketId, PlatformRows.Case c) {
    MarketApi.PlatformRef m = markets.platformRef(marketId).orElseThrow(() -> new BizException("NOT_FOUND", "市场不存在"));
    LocalDateTime now = now();
    List<PlatformRows.Record> records = mapper.records(marketId);
    Map<Long, MarketApi.OptionRef> opts = new LinkedHashMap<>();
    for (MarketApi.OptionRef o : m.options()) opts.put(o.optionId(), o);
    PlatformRows.Record proposal = c.proposalId == null ? null : records.stream().filter(r -> r.id.equals(c.proposalId)).findFirst().orElse(null);
    List<PlatformRows.Dispute> disputes = mapper.disputesOf(marketId);
    int open = (int) disputes.stream().filter(d -> "OPEN".equals(d.status)).count();
    CaseItem summary = item(c, m.publicNo(), m.category().name(), m.title(), m.status(), iso(m.eventEndAt()), open, proposal, opts, now);
    List<OptionView> options = m.options().stream().map(o -> new OptionView(String.valueOf(o.optionNo()), o.seq(), o.label())).toList();
    List<Record> rv = records.stream().map(r -> {
      Map<String, Object> d = detailOf(r);
      return new Record(String.valueOf(r.id), r.kind.toLowerCase(Locale.ROOT), optionNo(opts, r.optionId), r.refId == null ? null : String.valueOf(r.refId),
          (String) d.get("basis"), iso(r.actualStartAt), list(r.evidenceUrls), list(r.attachments), r.note, r.actor, iso(r.createdAt), d.isEmpty() ? null : d);
    }).toList();
    List<Dispute> dv = disputes.stream().map(x -> dispute(x, m.publicNo(), opts)).toList();
    List<Attachment> av = mapper.attachmentsOf(marketId).stream().map(ResolveDesk::attachment).toList();
    return new CaseDetail(summary, options, iso(c.actualStartAt), c.resolvedBy, iso(c.confirmedAt), lower(c.decision), rv, dv, av);
  }

  private Dispute dispute(PlatformRows.Dispute x, long marketNo, Map<Long, MarketApi.OptionRef> opts) {
    return new Dispute(String.valueOf(x.publicNo), String.valueOf(marketNo), x.userId, optionNo(opts, x.claimedOptionId), x.claimedOptionId == null, x.reason,
        x.link, x.status.toLowerCase(Locale.ROOT), x.decisionNote, x.handledBy, iso(x.handledAt), iso(x.receivedAt));
  }

  private static Attachment attachment(PlatformRows.Attachment a) {
    return new Attachment(String.valueOf(a.publicNo), a.contentType, a.sizeBytes, a.sha256, a.uploadedBy, iso(a.createdAt));
  }

  // =====================================================================
  // 存档截图
  // =====================================================================

  @Override
  public Attachment upload(long marketNo, byte[] content, long adminId) {
    MarketApi.MarketRef m = market(marketNo);
    ValidationException.Collector errs = new ValidationException.Collector();
    String ext = null;
    if (content == null || content.length == 0) errs.add("file", "required");
    else if (content.length > MAX_BYTES) errs.add("file", "tooLarge");
    else if ((ext = imageExt(content)) == null) errs.add("file", "invalidType");
    errs.throwIfAny();
    if (!VOIDABLE.contains(m.status())) throw new BizException("MARKET_NOT_RESOLVABLE", "市场现在不在裁决中（" + m.status().api() + "）");
    LocalDateTime now = now();
    PlatformRows.Attachment a = new PlatformRows.Attachment();
    a.publicNo = ids.next();
    a.marketId = m.marketId();
    a.storageKey = "resolve/" + m.publicNo() + "/" + a.publicNo + "." + ext;
    a.contentType = "png".equals(ext) ? "image/png" : "image/jpeg";
    a.sizeBytes = content.length;
    a.sha256 = sha256(content);
    a.uploadedBy = adminId;
    a.createdAt = now;
    // 先写文件、再写库：库里有记录就一定有文件（写库失败只留下一个没人引用的文件）
    storage.put(a.storageKey, content, a.contentType);
    mapper.insertAttachment(a);
    return attachment(a);
  }

  @Override
  public Optional<File> attachment(long attachmentNo) {
    PlatformRows.Attachment a = mapper.attachment(attachmentNo);
    if (a == null) return Optional.empty();
    byte[] b = storage.get(a.storageKey);
    return b == null ? Optional.empty() : Optional.of(new File(b, a.contentType));
  }

  /** JPG 以 FF D8 FF 开头，PNG 以 89 50 4E 47 0D 0A 1A 0A 开头；其他一律拒绝（和 KYC 证件照同样的判断，不信任浏览器报的类型） */
  static String imageExt(byte[] b) {
    if (b == null) return null;
    if (b.length >= 3 && (b[0] & 0xff) == 0xFF && (b[1] & 0xff) == 0xD8 && (b[2] & 0xff) == 0xFF) return "jpg";
    byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    if (b.length < 8) return null;
    for (int i = 0; i < 8; i++) if (b[i] != png[i]) return null;
    return "png";
  }

  // =====================================================================
  // 提议、确认、退回、直接确认
  // =====================================================================

  /** 校验过的提议内容 */
  record Parsed(boolean isVoid, Long optionId, String basis, Instant actualStartAt, List<String> urls, List<String> attachmentNos, String note) {}

  @Override
  public List<ValidationException.Field> checkProposal(long marketNo, ProposalInput in) {
    MarketApi.MarketRef m = market(marketNo);
    MarketApi.PlatformRef p = markets.platformRef(m.marketId()).orElseThrow(() -> new BizException("NOT_FOUND", "市场不存在"));
    List<ValidationException.Field> errs = new ArrayList<>();
    parse(p, in, errs);
    return errs;
  }

  private Parsed parse(MarketApi.PlatformRef m, ProposalInput in, List<ValidationException.Field> errs) {
    ProposalInput x = in == null ? new ProposalInput(null, null, null, null, null, null, null) : in;
    boolean isVoid = Boolean.TRUE.equals(x.voidMarket());
    Long optionId = null;
    if (isVoid) {
      if (x.optionNo() != null && !x.optionNo().isBlank()) errs.add(new ValidationException.Field("optionNo", "notAllowed"));
    } else if (x.optionNo() == null || x.optionNo().isBlank()) {
      errs.add(new ValidationException.Field("optionNo", "required"));
    } else {
      optionId = m.options().stream().filter(o -> String.valueOf(o.optionNo()).equals(x.optionNo().strip())).findFirst().map(MarketApi.OptionRef::optionId)
          .orElse(null);
      if (optionId == null) errs.add(new ValidationException.Field("optionNo", "invalid"));
    }
    String basis = x.basis() == null || x.basis().isBlank() ? null : x.basis().strip().toUpperCase(Locale.ROOT);
    if (isVoid) {
      if (basis == null) errs.add(new ValidationException.Field("basis", "required"));
      else if (!VOID_BASES.contains(basis)) errs.add(new ValidationException.Field("basis", "invalid"));
    } else {
      if (basis == null) basis = "NORMAL";
      else if (!RESULT_BASES.contains(basis)) errs.add(new ValidationException.Field("basis", "invalid"));
    }
    Instant start = null;
    if (x.actualStartAt() != null && !x.actualStartAt().isBlank()) {
      if (isVoid) errs.add(new ValidationException.Field("actualStartAt", "notAllowed"));
      else {
        try {
          start = Instant.parse(x.actualStartAt().strip()).truncatedTo(ChronoUnit.MILLIS);
          if (start.isAfter(Instant.now(clock))) errs.add(new ValidationException.Field("actualStartAt", "future"));
        } catch (DateTimeParseException e) {
          errs.add(new ValidationException.Field("actualStartAt", "invalid"));
        }
      }
    }
    List<String> urls = new ArrayList<>();
    List<String> rawUrls = x.evidenceUrls() == null ? List.of() : x.evidenceUrls();
    if (rawUrls.stream().allMatch(u -> u == null || u.isBlank())) errs.add(new ValidationException.Field("evidenceUrls", "required"));
    else if (rawUrls.size() > MAX_URLS) errs.add(new ValidationException.Field("evidenceUrls", "tooMany"));
    else {
      for (int i = 0; i < rawUrls.size(); i++) {
        String u = rawUrls.get(i) == null ? "" : rawUrls.get(i).strip();
        if (u.isEmpty()) continue;
        if (u.length() > URL_MAX) errs.add(new ValidationException.Field("evidenceUrls[" + i + "]", "tooLong"));
        else if (!isHttpUrl(u)) errs.add(new ValidationException.Field("evidenceUrls[" + i + "]", "invalid"));
        else urls.add(u);
      }
    }
    List<String> attachments = new ArrayList<>();
    List<String> rawAtt = x.attachmentNos() == null ? List.of() : x.attachmentNos();
    if (rawAtt.size() > MAX_ATTACHMENTS) errs.add(new ValidationException.Field("attachmentNos", "tooMany"));
    else {
      for (int i = 0; i < rawAtt.size(); i++) {
        PlatformRows.Attachment a = null;
        String no = rawAtt.get(i) == null ? "" : rawAtt.get(i).strip();
        if (no.matches("\\d{1,19}")) a = mapper.attachment(Long.parseLong(no));
        if (a == null || a.marketId != m.marketId()) errs.add(new ValidationException.Field("attachmentNos[" + i + "]", "invalid"));
        else if (!attachments.contains(no)) attachments.add(no);
      }
    }
    String note = x.note() == null ? "" : x.note().strip();
    if (note.isEmpty()) errs.add(new ValidationException.Field("note", "required"));
    else if (note.length() > NOTE_MAX) errs.add(new ValidationException.Field("note", "tooLong"));
    return new Parsed(isVoid, optionId, basis, start, urls, attachments, note);
  }

  static boolean isHttpUrl(String u) {
    try {
      URI uri = new URI(u);
      String s = uri.getScheme();
      return (s != null && (s.equalsIgnoreCase("http") || s.equalsIgnoreCase("https"))) && uri.getHost() != null && !uri.getHost().isBlank();
    } catch (Exception e) {
      return false;
    }
  }

  @Override
  public CaseDetail propose(long marketNo, ProposalInput in, long adminId) {
    long id = market(marketNo).marketId();
    LocalDateTime now = now();
    inTx(() -> {
      MarketApi.PlatformRef m = markets.lockPlatform(id);
      requireResolvable(m);
      PlatformRows.Case c = lockOpenCase(m);
      if (c.holdKind != null) throw onHold(c);
      if (c.proposalId != null) throw new BizException("PROPOSAL_PENDING", "已经有一条待确认的提议，先确认或退回");
      List<ValidationException.Field> errs = new ArrayList<>();
      Parsed p = parse(m, in, errs);
      if (!errs.isEmpty()) throw new ValidationException(errs);
      PlatformRows.Record r = proposalRecord(m, p, String.valueOf(adminId), now);
      if (mapper.setProposal(id, r.id, now, now.plus(params.confirmWindow())) != 1) throw stateChanged();
      Map<String, String> v = new LinkedHashMap<>();
      v.put("marketNo", String.valueOf(m.publicNo()));
      v.put("market", m.title().zhCN());
      v.put("marketEn", m.title().en());
      MarketApi.OptionRef o = option(m, p.optionId());
      v.put("option", o == null ? "VOID" : o.label().zhCN());
      v.put("optionEn", o == null ? "VOID" : o.label().en());
      v.put("proposer", String.valueOf(adminId));
      v.put("dueAt", NOTICE_TIME.format(now.plus(params.confirmWindow())));
      afterCommit(() -> alert("RESOLVE_PROPOSED", v));
      return null;
    });
    return detail(marketNo).orElseThrow();
  }

  private PlatformRows.Record proposalRecord(MarketApi.PlatformRef m, Parsed p, String actor, LocalDateTime now) {
    PlatformRows.Record r = record(m.marketId(), PROPOSE, p.optionId(), null, actor, now);
    r.actualStartAt = p.actualStartAt() == null ? null : ldt(p.actualStartAt());
    r.evidenceUrls = json.writeValueAsString(p.urls());
    r.attachments = json.writeValueAsString(p.attachmentNos());
    r.detail = json.writeValueAsString(Map.of("outcome", p.isVoid() ? "void" : "result", "basis", p.basis()));
    r.note = p.note();
    mapper.insertRecord(r);
    return r;
  }

  @Override
  public CaseDetail confirm(long marketNo, String proposalId, long adminId, boolean canVoid) {
    long id = market(marketNo).marketId();
    LocalDateTime now = now();
    boolean voided = Boolean.TRUE.equals(inTx(() -> {
      MarketApi.PlatformRef m = markets.lockPlatform(id);
      requireResolvable(m);
      PlatformRows.Case c = lockOpenCase(m);
      PlatformRows.Record prop = currentProposal(c, proposalId);
      return applyConfirm(m, c, prop, String.valueOf(adminId), canVoid, now);
    }));
    log.info("市场 {} 确认提议 {}：{}", marketNo, proposalId, voided ? "作废" : "进入公示");
    return detail(marketNo).orElseThrow();
  }

  @Override
  public CaseDetail returnProposal(long marketNo, String proposalId, String reason, long adminId) {
    long id = market(marketNo).marketId();
    String r = reason == null ? "" : reason.strip();
    if (r.isEmpty()) throw new ValidationException(List.of(new ValidationException.Field("reason", "required")));
    LocalDateTime now = now();
    inTx(() -> {
      MarketApi.PlatformRef m = markets.lockPlatform(id);
      requireResolvable(m);
      PlatformRows.Case c = lockOpenCase(m);
      PlatformRows.Record prop = currentProposal(c, proposalId);
      PlatformRows.Record rec = record(id, RETURN, prop.optionId, prop.id, String.valueOf(adminId), now);
      rec.note = cut(r, NOTE_MAX);
      mapper.insertRecord(rec);
      if (mapper.clearProposal(id, prop.id, now) != 1) throw stateChanged();
      return null;
    });
    return detail(marketNo).orElseThrow();
  }

  @Override
  public CaseDetail decide(long marketNo, ProposalInput in, long adminId, boolean canVoid) {
    long id = market(marketNo).marketId();
    LocalDateTime now = now();
    inTx(() -> {
      MarketApi.PlatformRef m = markets.lockPlatform(id);
      requireResolvable(m);
      PlatformRows.Case c = lockOpenCase(m);
      if (c.holdKind != null) throw onHold(c);
      if (c.proposalId != null) throw new BizException("PROPOSAL_PENDING", "已经有一条待确认的提议，先确认或退回");
      List<ValidationException.Field> errs = new ArrayList<>();
      Parsed p = parse(m, in, errs);
      if (!errs.isEmpty()) throw new ValidationException(errs);
      if (p.isVoid() && !canVoid) throw new BizException("FORBIDDEN", "作废要 resolve:void 权限");
      PlatformRows.Record prop = proposalRecord(m, p, String.valueOf(adminId), now);
      return applyConfirm(m, c, prop, String.valueOf(adminId), canVoid, now);
    });
    return detail(marketNo).orElseThrow();
  }

  /**
   * 确认一条提议（调用方已锁市场行和进度行）：结果 → 公示中（拟定结果、争议截止时间，通知 #10）；作废 → 已作废（全额退款，原因 = 提议的理由）。
   *
   * @return 是否作废
   */
  private boolean applyConfirm(MarketApi.PlatformRef m, PlatformRows.Case c, PlatformRows.Record prop, String actor, boolean canVoid, LocalDateTime now) {
    Map<String, Object> d = detailOf(prop);
    boolean isVoid = "void".equals(d.get("outcome"));
    if (isVoid && !canVoid) throw new BizException("FORBIDDEN", "确认作废的提议要 resolve:void 权限");
    PlatformRows.Record rec = record(m.marketId(), isVoid ? VOID : CONFIRM, prop.optionId, prop.id, actor, now);
    rec.actualStartAt = prop.actualStartAt;
    rec.evidenceUrls = prop.evidenceUrls;
    rec.attachments = prop.attachments;
    rec.detail = prop.detail;
    rec.note = prop.note;
    mapper.insertRecord(rec);
    if (isVoid) {
      if (mapper.clearProposal(m.marketId(), prop.id, now) != 1) throw stateChanged();
      if (!markets.transition(m.marketId(), MarketStatus.PENDING_RESOLUTION, MarketStatus.VOIDED, actor, cut(prop.note, 500))) throw stateChanged();
      return true;
    }
    LocalDateTime deadline = now.plus(params.disputeWindow(m));
    Instant start = prop.actualStartAt == null ? null : instant(prop.actualStartAt);
    if (!markets.publicize(m.marketId(), prop.optionId, start, actor, instant(deadline), actor)) throw stateChanged();
    if (mapper.confirmCase(m.marketId(), prop.optionId, prop.actualStartAt, actor, rec.id, deadline, now) != 1) throw stateChanged();
    // #10：有下注的客户（站内信、推送）
    MarketApi.OptionRef o = option(m, prop.optionId);
    List<NotifyApi.Notice> notices = new ArrayList<>();
    for (long uid : pool.bettors(m.marketId())) {
      Map<String, String> v = new LinkedHashMap<>();
      v.put("market", m.title().zhCN());
      v.put("marketEn", m.title().en());
      v.put("marketTw", m.title().zhTW());
      v.put("option", o.label().zhCN());
      v.put("optionEn", o.label().en());
      v.put("optionTw", o.label().zhTW());
      v.put("deadline", NOTICE_TIME.format(deadline));
      v.put("marketNo", String.valueOf(m.publicNo())); // 通知中心点进市场（S11）
      notices.add(new NotifyApi.Notice(uid, v));
    }
    notify.toUsers("RESULT_PUBLICIZED", notices);
    return false;
  }

  private PlatformRows.Record currentProposal(PlatformRows.Case c, String proposalId) {
    if (c.proposalId == null || proposalId == null || !String.valueOf(c.proposalId).equals(proposalId.strip())) {
      throw new BizException("PROPOSAL_CHANGED", "这条提议已经不是待确认的了（被确认、退回或换了），请刷新");
    }
    return mapper.record(c.proposalId);
  }

  // =====================================================================
  // E6 延期、E7 腰斩
  // =====================================================================

  @Override
  public CaseDetail hold(long marketNo, String kind, String reason, long adminId) {
    long id = market(marketNo).marketId();
    String k = kind == null ? "" : kind.strip().toUpperCase(Locale.ROOT);
    String r = reason == null ? "" : reason.strip();
    ValidationException.Collector errs = new ValidationException.Collector();
    if (!k.equals("POSTPONED") && !k.equals("ABANDONED")) errs.add("kind", k.isEmpty() ? "required" : "invalid");
    if (r.isEmpty()) errs.add("reason", "required");
    errs.throwIfAny();
    LocalDateTime now = now();
    inTx(() -> {
      MarketApi.PlatformRef m = markets.lockPlatform(id);
      requireResolvable(m);
      PlatformRows.Case c = lockOpenCase(m);
      if (c.holdKind != null) throw onHold(c);
      if (c.proposalId != null) throw new BizException("PROPOSAL_PENDING", "已经有一条待确认的提议，先确认或退回");
      LocalDateTime until = holdUntil(m, k, now);
      if (!until.isAfter(now)) throw new ValidationException(List.of(new ValidationException.Field("kind", "expired")));
      if (!applyHold(m, k, until, cut(r, NOTE_MAX), String.valueOf(adminId), Map.of(), now)) throw stateChanged();
      return null;
    });
    return detail(marketNo).orElseThrow();
  }

  /** E6：原定开赛后 48 小时内完赛的按实际结果，超过的作废；E7：48 小时内没有宣布的作废（从标记时算） */
  private static LocalDateTime holdUntil(MarketApi.PlatformRef m, String kind, LocalDateTime now) {
    return "POSTPONED".equals(kind) ? ldt(m.eventStartAt()).plus(PlatformParams.HOLD_MAX) : now.plus(PlatformParams.HOLD_MAX);
  }

  /** 写 HOLD 记录、标记等待（调用方已锁市场行、进度行）；进度行状态不对返回 false */
  private boolean applyHold(MarketApi.PlatformRef m, String kind, LocalDateTime until, String note, String actor, Map<String, Object> extra,
      LocalDateTime now) {
    PlatformRows.Record rec = record(m.marketId(), HOLD, null, null, actor, now);
    Map<String, Object> d = new LinkedHashMap<>(extra);
    d.put("hold", kind);
    d.put("until", iso(until));
    rec.detail = json.writeValueAsString(d);
    rec.note = note;
    mapper.insertRecord(rec);
    return mapper.setHold(m.marketId(), kind, until, now) == 1;
  }

  /**
   * 系统标记 E6 延期 / E7 腰斩等待（forecast S8：体育数据源报告延期、中断时自动触发，和裁决台的"标记等待"效果相同：等待期间不能提议，
   * 48 小时内由人解除后照常提议，到时间没解除系统作废）。<b>必须在调用方的事务里</b>（这里锁市场行、进度行；进度行要已经建好，见 {@link CaseBook#open}）。
   * 不能标记（市场不在待裁决、结算已开始、已经在等待、有待确认的提议、E6 的 48 小时已经过了）时返回 false、什么都不改。
   *
   * @param kind POSTPONED / ABANDONED
   * @param detail 依据（数据源报告的状态等），写进 HOLD 记录
   */
  public boolean systemHold(long marketId, String kind, String reason, Map<String, Object> detail) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("systemHold 必须在事务里调用");
    LocalDateTime now = now();
    MarketApi.PlatformRef m = markets.lockPlatform(marketId);
    if (m.status() != MarketStatus.PENDING_RESOLUTION || m.settlePhase() != SettlePhase.NONE) return false;
    PlatformRows.Case c = mapper.lockCase(marketId);
    if (c == null || !"OPEN".equals(c.stage) || c.holdKind != null || c.proposalId != null) return false;
    LocalDateTime until = holdUntil(m, kind, now);
    if (!until.isAfter(now)) return false;
    return applyHold(m, kind, until, cut(reason, NOTE_MAX), SYSTEM, detail == null ? Map.of() : detail, now);
  }

  @Override
  public CaseDetail release(long marketNo, String reason, long adminId) {
    long id = market(marketNo).marketId();
    String r = reason == null ? "" : reason.strip();
    if (r.isEmpty()) throw new ValidationException(List.of(new ValidationException.Field("reason", "required")));
    LocalDateTime now = now();
    inTx(() -> {
      MarketApi.PlatformRef m = markets.lockPlatform(id);
      requireResolvable(m);
      PlatformRows.Case c = lockOpenCase(m);
      if (c.holdKind == null) throw new BizException("NOT_ON_HOLD", "这个市场没有在等待（延期 / 腰斩）");
      PlatformRows.Record rec = record(id, RELEASE, null, null, String.valueOf(adminId), now);
      rec.detail = json.writeValueAsString(Map.of("hold", c.holdKind));
      rec.note = cut(r, NOTE_MAX);
      mapper.insertRecord(rec);
      // 解除以后重新算提议时限（等待期间不能提议）
      if (mapper.clearHold(id, now.plus(params.proposeWindow(m.category())), now) != 1) throw stateChanged();
      return null;
    });
    return detail(marketNo).orElseThrow();
  }

  // =====================================================================
  // 人工作废
  // =====================================================================

  @Override
  public void voidMarket(long marketNo, String reason, long adminId) {
    long id = market(marketNo).marketId();
    String r = reason == null ? "" : reason.strip();
    if (r.isEmpty()) throw new ValidationException(List.of(new ValidationException.Field("reason", "required")));
    LocalDateTime now = now();
    inTx(() -> {
      MarketApi.PlatformRef m = markets.lockPlatform(id);
      if (!VOIDABLE.contains(m.status()) || m.settlePhase() != SettlePhase.NONE) {
        throw new BizException("MARKET_NOT_VOIDABLE", "只有待裁决、公示中、争议中且还没开始结算的市场能作废（截止前请用取消）");
      }
      PlatformRows.Case c = mapper.lockCase(id);
      PlatformRows.Record rec = record(id, VOID, null, c == null ? null : c.proposalId, String.valueOf(adminId), now);
      rec.detail = json.writeValueAsString(Map.of("outcome", "void", "basis", "MANUAL"));
      rec.note = cut(r, NOTE_MAX);
      mapper.insertRecord(rec);
      // 待处理的争议一起结束：结论 = 作废（#15）
      List<PlatformRows.Dispute> open = mapper.openDisputes(id);
      if (!open.isEmpty()) {
        mapper.decideDisputes(id, "VOIDED", cut(r, NOTE_MAX), adminId, now);
        notifyDecided(m, open, "VOIDED", null, cut(r, NOTE_MAX));
      }
      if (!markets.transition(id, m.status(), MarketStatus.VOIDED, String.valueOf(adminId), cut(r, 500))) throw stateChanged();
      return null;
    });
  }

  // =====================================================================
  // 争议
  // =====================================================================

  @Override
  public DisputeReceipt dispute(long marketNo, long userId, DisputeInput in, Instant receivedAt) {
    DisputeInput x = in == null ? new DisputeInput(null, null, null) : in;
    ValidationException.Collector errs = new ValidationException.Collector();
    String claim = x.claimedOptionNo() == null ? "" : x.claimedOptionNo().strip();
    if (claim.isEmpty()) errs.add("claimedOptionNo", "required");
    String reason = x.reason() == null ? "" : x.reason().strip();
    if (reason.isEmpty()) errs.add("reason", "required");
    else if (reason.length() > NOTE_MAX) errs.add("reason", "tooLong");
    String link = x.link() == null || x.link().isBlank() ? null : x.link().strip();
    if (link != null && link.length() > URL_MAX) errs.add("link", "tooLong");
    else if (link != null && !isHttpUrl(link)) errs.add("link", "invalid");
    MarketApi.MarketRef ref = markets.find(marketNo).orElse(null);
    if (ref == null || ref.status() == MarketStatus.DRAFT) throw new BizException("NOT_FOUND", "市场不存在");
    long id = ref.marketId();
    Long claimed = null;
    if (!claim.isEmpty() && !claim.equalsIgnoreCase("void")) {
      claimed = markets.options(id).stream().filter(o -> String.valueOf(o.optionNo()).equals(claim)).findFirst().map(MarketApi.OptionRef::optionId).orElse(null);
      if (claimed == null) errs.add("claimedOptionNo", "invalid");
    }
    errs.throwIfAny();
    LocalDateTime now = now();
    LocalDateTime received = ldt(receivedAt);
    Long claimedOption = claimed;
    PlatformRows.Dispute saved = inTx(() -> {
      MarketApi.PlatformRef m = markets.lockPlatform(id);
      PlatformRows.Case c = mapper.lockCase(id);
      // 只在公示中（或已经有人提交、争议中且还没给结论）、结算还没开始、收到时间 < 争议截止时间
      boolean open = (m.status() == MarketStatus.PUBLICIZING || m.status() == MarketStatus.DISPUTED) && m.settlePhase() == SettlePhase.NONE && c != null
          && c.decision == null && m.disputeDeadlineAt() != null && received.isBefore(ldt(m.disputeDeadlineAt()));
      if (!open) throw new BizException("DISPUTE_CLOSED", "这个市场现在不能提交争议（不在公示期内）");
      if (!pool.hasPosition(userId, id)) throw new BizException("DISPUTE_NOT_BETTOR", "只有在这个市场有下注的客户能提交争议");
      if (claimedOption != null && claimedOption.equals(m.winningOptionId())) {
        throw new ValidationException(List.of(new ValidationException.Field("claimedOptionNo", "sameAsResult")));
      }
      if (mapper.disputeOfUser(id, userId) != null) throw disputeExists();
      PlatformRows.Dispute d = new PlatformRows.Dispute();
      d.publicNo = ids.next();
      d.marketId = id;
      d.userId = userId;
      d.claimedOptionId = claimedOption;
      d.reason = reason;
      d.link = link;
      d.receivedAt = received;
      d.createdAt = now;
      try {
        mapper.insertDispute(d);
      } catch (DuplicateKeyException e) {
        throw disputeExists();
      }
      if (m.status() == MarketStatus.PUBLICIZING) {
        if (!markets.transition(id, MarketStatus.PUBLICIZING, MarketStatus.DISPUTED, CUSTOMER, null)) throw stateChanged();
        mapper.disputeOpened(id, now.plus(params.disputeHandleWindow()), now);
      }
      PlatformRows.Case after = mapper.findCase(id);
      Map<String, String> v = new LinkedHashMap<>();
      v.put("marketNo", String.valueOf(m.publicNo()));
      v.put("market", m.title().zhCN());
      v.put("marketEn", m.title().en());
      v.put("count", String.valueOf(mapper.countDisputes(id)));
      v.put("dueAt", after.disputeDueAt == null ? "-" : NOTICE_TIME.format(after.disputeDueAt));
      afterCommit(() -> alert("DISPUTE_NEW", v));
      d.status = "OPEN";
      return d;
    });
    return new DisputeReceipt(String.valueOf(saved.publicNo), String.valueOf(marketNo), claimedOption == null ? null : claim, claimedOption == null, "open",
        iso(saved.receivedAt));
  }

  private static BizException disputeExists() {
    return new BizException("DISPUTE_EXISTS", "你已经对这个市场提交过争议（每人每市场 1 次）");
  }

  @Override
  public Page<Dispute> disputes(String status, int page, int pageSize) {
    String st = status == null || status.isBlank() ? null : status.strip().toUpperCase(Locale.ROOT);
    if (st != null && !st.equals("OPEN") && !st.equals("HANDLED")) throw new ValidationException(List.of(new ValidationException.Field("status", "invalid")));
    int p = Math.max(1, page);
    int size = Math.min(100, Math.max(1, pageSize));
    List<PlatformRows.Dispute> rows = mapper.listDisputes(st, (p - 1) * size, size);
    Map<Long, MarketApi.MarketRef> refs = markets.refs(rows.stream().map(d -> d.marketId).distinct().toList());
    Map<Long, MarketApi.OptionRef> opts = markets.optionsById(rows.stream().map(d -> d.claimedOptionId).filter(x -> x != null).distinct().toList());
    List<Dispute> items = rows.stream().map(d -> dispute(d, refs.containsKey(d.marketId) ? refs.get(d.marketId).publicNo() : 0, opts)).toList();
    return new Page<>(items, mapper.countAllDisputes(st), p, size);
  }

  @Override
  public CaseDetail decideDisputes(long marketNo, DecisionInput in, long adminId) {
    long id = market(marketNo).marketId();
    DecisionInput x = in == null ? new DecisionInput(null, null, null) : in;
    String decision = x.decision() == null ? "" : x.decision().strip().toUpperCase(Locale.ROOT);
    String note = x.note() == null ? "" : x.note().strip();
    ValidationException.Collector errs = new ValidationException.Collector();
    if (!Set.of("UPHOLD", "OVERTURN", "VOID").contains(decision)) errs.add("decision", decision.isEmpty() ? "required" : "invalid");
    if (note.isEmpty()) errs.add("note", "required");
    else if (note.length() > NOTE_MAX) errs.add("note", "tooLong");
    MarketApi.PlatformRef peek = markets.platformRef(id).orElseThrow(() -> new BizException("NOT_FOUND", "市场不存在"));
    Long newOption = null;
    if ("OVERTURN".equals(decision)) {
      String no = x.optionNo() == null ? "" : x.optionNo().strip();
      if (no.isEmpty()) errs.add("optionNo", "required");
      else {
        newOption = peek.options().stream().filter(o -> String.valueOf(o.optionNo()).equals(no)).findFirst().map(MarketApi.OptionRef::optionId).orElse(null);
        if (newOption == null) errs.add("optionNo", "invalid");
        else if (newOption.equals(peek.winningOptionId())) errs.add("optionNo", "sameAsResult");
      }
    }
    errs.throwIfAny();
    LocalDateTime now = now();
    Long overturnTo = newOption;
    inTx(() -> {
      MarketApi.PlatformRef m = markets.lockPlatform(id);
      if (m.status() != MarketStatus.DISPUTED || m.settlePhase() != SettlePhase.NONE) throw new BizException("DISPUTE_NOT_OPEN", "这个市场没有待处理的争议");
      PlatformRows.Case c = mapper.lockCase(id);
      List<PlatformRows.Dispute> open = mapper.openDisputes(id);
      if (c == null || c.decision != null || open.isEmpty()) throw new BizException("DISPUTE_NOT_OPEN", "这个市场没有待处理的争议");
      String kind = switch (decision) {
        case "UPHOLD" -> DISPUTE_UPHOLD;
        case "OVERTURN" -> DISPUTE_OVERTURN;
        default -> DISPUTE_VOID;
      };
      Long finalOption = switch (decision) {
        case "UPHOLD" -> c.resultOptionId;
        case "OVERTURN" -> overturnTo;
        default -> null;
      };
      String status = switch (decision) {
        case "UPHOLD" -> "UPHELD";
        case "OVERTURN" -> "OVERTURNED";
        default -> "VOIDED";
      };
      PlatformRows.Record rec = record(id, kind, finalOption, c.confirmRecordId, String.valueOf(adminId), now);
      rec.detail = json.writeValueAsString(Map.of("decision", decision.toLowerCase(Locale.ROOT), "disputes", open.size()));
      rec.note = note;
      mapper.insertRecord(rec);
      if (mapper.decideDisputes(id, status, note, adminId, now) != open.size()) throw stateChanged();
      // 改判的：公开的裁决人编号是处理争议的管理员（需求第 4.4 节）
      String resolvedBy = "OVERTURN".equals(decision) ? String.valueOf(adminId) : c.resolvedBy;
      if (mapper.setDecision(id, decision, rec.id, finalOption, resolvedBy, now) != 1) throw stateChanged();
      notifyDecided(m, open, status, option(m, finalOption), note);
      if ("OVERTURN".equals(decision)) {
        MarketApi.OptionRef from = option(m, c.resultOptionId);
        MarketApi.OptionRef to = option(m, finalOption);
        List<NotifyApi.Notice> notices = new ArrayList<>();
        for (long uid : pool.bettors(id)) {
          Map<String, String> v = new LinkedHashMap<>();
          v.put("market", m.title().zhCN());
          v.put("marketEn", m.title().en());
          v.put("marketTw", m.title().zhTW());
          v.put("option", to.label().zhCN());
          v.put("optionEn", to.label().en());
          v.put("optionTw", to.label().zhTW());
          v.put("oldOption", from == null ? "" : from.label().zhCN());
          v.put("oldOptionEn", from == null ? "" : from.label().en());
          v.put("oldOptionTw", from == null ? "" : from.label().zhTW());
          v.put("marketNo", String.valueOf(m.publicNo())); // 通知中心点进市场（S11）
          notices.add(new NotifyApi.Notice(uid, v));
        }
        notify.toUsers("RESULT_OVERTURNED", notices);
      }
      if ("VOID".equals(decision) && !markets.transition(id, MarketStatus.DISPUTED, MarketStatus.VOIDED, String.valueOf(adminId), cut(note, 500))) {
        throw stateChanged();
      }
      return null;
    });
    // 维持 / 改判：处理完直接结算（不再公示第二轮，E12）。失败或进程退出时，公示任务下一轮接着交给结算
    if (!"VOID".equals(decision)) publicity.requestSettle(id, MarketStatus.DISPUTED);
    return detail(marketNo).orElseThrow();
  }

  /** #15：争议人（每人一条）；status UPHELD / OVERTURNED / VOIDED（通知模块翻译），option 最终结果（作废为空，这一行不显示） */
  private void notifyDecided(MarketApi.PlatformRef m, List<PlatformRows.Dispute> disputes, String status, MarketApi.OptionRef finalOption, String note) {
    List<NotifyApi.Notice> notices = new ArrayList<>();
    for (PlatformRows.Dispute d : disputes) {
      Map<String, String> v = new LinkedHashMap<>();
      v.put("market", m.title().zhCN());
      v.put("marketEn", m.title().en());
      v.put("marketTw", m.title().zhTW());
      v.put("decision", status);
      if (finalOption != null) {
        v.put("option", finalOption.label().zhCN());
        v.put("optionEn", finalOption.label().en());
        v.put("optionTw", finalOption.label().zhTW());
      }
      v.put("note", note);
      v.put("marketNo", String.valueOf(m.publicNo())); // 通知中心点进市场（S11）
      notices.add(new NotifyApi.Notice(d.userId, v));
    }
    notify.toUsers("DISPUTE_DECIDED", notices);
  }

  // =====================================================================
  // 小工具
  // =====================================================================

  private MarketApi.MarketRef market(long marketNo) {
    MarketApi.MarketRef m = markets.find(marketNo).orElseThrow(() -> new BizException("NOT_FOUND", "市场不存在"));
    if (m.status() == MarketStatus.DRAFT) throw new BizException("NOT_FOUND", "市场不存在");
    return m;
  }

  private static void requireResolvable(MarketApi.PlatformRef m) {
    if (m.status() != MarketStatus.PENDING_RESOLUTION || m.settlePhase() != SettlePhase.NONE) {
      throw new BizException("MARKET_NOT_RESOLVABLE", "市场现在不在待裁决（" + m.status().api() + "）");
    }
  }

  /** 进度行（加锁）；没有 = 加密自动裁决还在处理（还没转人工），不能人工提议 */
  private PlatformRows.Case lockOpenCase(MarketApi.PlatformRef m) {
    PlatformRows.Case c = mapper.lockCase(m.marketId());
    if (c == null) throw new BizException("RESOLVE_AUTO_IN_PROGRESS", "自动裁决还在处理这个市场，转人工以后才能人工裁决（要停止请作废）");
    return c;
  }

  private static BizException onHold(PlatformRows.Case c) {
    return new BizException("RESOLVE_ON_HOLD", "这个市场在等待（" + ("POSTPONED".equals(c.holdKind) ? "E6 延期" : "E7 腰斩") + "，到 " + iso(c.holdUntil)
        + "），等待期间不能提议；已完赛或官方已宣布请先解除");
  }

  private static BizException stateChanged() {
    return new BizException("MARKET_STATE_CHANGED", "市场状态刚被别人改过，请刷新后再操作");
  }

  private static MarketApi.OptionRef option(MarketApi.PlatformRef m, Long optionId) {
    if (optionId == null) return null;
    return m.options().stream().filter(o -> o.optionId() == optionId).findFirst().orElse(null);
  }

  private PlatformRows.Record record(long marketId, String kind, Long optionId, Long refId, String actor, LocalDateTime now) {
    PlatformRows.Record r = new PlatformRows.Record();
    r.marketId = marketId;
    r.kind = kind;
    r.optionId = optionId;
    r.refId = refId;
    r.actor = actor;
    r.createdAt = now;
    return r;
  }

  Map<String, Object> detailOf(PlatformRows.Record r) {
    if (r.detail == null || r.detail.isBlank()) return Map.of();
    Map<String, Object> d = json.readValue(r.detail, new TypeReference<Map<String, Object>>() {});
    return d == null ? Map.of() : d;
  }

  List<String> list(String jsonArray) {
    if (jsonArray == null || jsonArray.isBlank()) return List.of();
    List<String> l = json.readValue(jsonArray, new TypeReference<List<String>>() {});
    return l == null ? List.of() : l;
  }

  private void alert(String template, Map<String, String> v) {
    try {
      staff.notifyStaff(template, v);
    } catch (RuntimeException e) {
      log.error("员工提醒 {} 发送失败（市场 {}）", template, v.get("marketNo"), e);
    }
  }

  private static void afterCommit(Runnable r) {
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
      @Override
      public void afterCommit() {
        r.run();
      }
    });
  }

  private <T> T inTx(Supplier<T> s) {
    return tx.execute(st -> s.get());
  }

  private static String optionNo(Map<Long, MarketApi.OptionRef> opts, Long optionId) {
    return optionId == null || !opts.containsKey(optionId) ? null : String.valueOf(opts.get(optionId).optionNo());
  }

  static String lower(String s) {
    return s == null ? null : s.toLowerCase(Locale.ROOT);
  }

  static String iso(LocalDateTime t) {
    return t == null ? null : t.toInstant(ZoneOffset.UTC).toString();
  }

  static String iso(Instant t) {
    return t == null ? null : t.toString();
  }

  static LocalDateTime ldt(Instant i) {
    return LocalDateTime.ofInstant(i.truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
  }

  static Instant instant(LocalDateTime t) {
    return t.toInstant(ZoneOffset.UTC);
  }

  static String cut(String s, int n) {
    return s == null || s.length() <= n ? s : s.substring(0, n);
  }

  static String sha256(byte[] b) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
