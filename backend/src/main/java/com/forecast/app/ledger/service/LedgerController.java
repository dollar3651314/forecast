package com.forecast.app.ledger.service;

import com.forecast.app.common.error.ValidationException;
import com.forecast.app.common.web.CurrentUser;
import com.forecast.app.ledger.api.AccountSnapshot;
import com.forecast.app.ledger.api.LedgerApi;
import com.forecast.app.ledger.api.MarketLabels;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 客户端的账户和资金流水接口（需要登录）。复制源的 {@code GET /api/trading/account}、{@code GET /api/trading/ledger}（交易控制器）
 * 删掉差价合约的字段后搬到这里（forecast S1）。字段名和客户端 web/client/src/api/types.ts 一致，金额一律用字符串。
 */
@RestController
class LedgerController {

  /**
   * 账户的三项（S2）：available 可用余额（可以提现、下注）、betLocked 下注锁定（客户端"下注中"）、withdrawFrozen 提现冻结；
   * balance 是客户看到的余额 = available + betLocked（不含提现冻结，和 S1 的含义一致）。
   */
  record AccountView(String currency, String balance, String available, String betLocked, String withdrawFrozen) {}

  /**
   * 资金流水的一条；ref 是相关的网络（例如充值 TRC20），没有时为空字符串；market 是相关的市场（下注、以后的派彩、退款，AC-39），
   * 没有时为 null。
   */
  record LedgerView(String id, String type, String amount, String ref, String time, MarketRef market) {}

  /** 流水里的市场：对外编号（客户端用它打开市场详情）和三种语言的题目 */
  record MarketRef(String publicNo, Title title) {}

  record Title(String en, String zhCN, String zhTW) {}

  record Page<T>(List<T> items, long total, int page, int pageSize) {}

  /**
   * 客户端流水类型 → 账本类型（只看可用余额的变动）。S4 加下注（BET_LOCK 的"可用 −b"那一条）；S5 加派彩、抽成、退款（REFUND 的"可用 +b"那一条）；S7 加补偿（E13）
   * （开发记录/S2-账本拆分.md、S4-下注奖池.md）。没列在这里的类型不会出现在客户端流水里。
   */
  static final Map<String, List<String>> LEDGER_TYPES = new LinkedHashMap<>();

  static {
    LEDGER_TYPES.put("deposit", List.of("DEPOSIT", "DEPOSIT_REVERSAL"));
    LEDGER_TYPES.put("withdraw", List.of("WITHDRAW_FREEZE"));
    LEDGER_TYPES.put("withdrawRefund", List.of("WITHDRAW_UNFREEZE"));
    LEDGER_TYPES.put("bet", List.of("BET_LOCK"));
    // S5：派彩（本金 + 盈利）、抽成、下注退回（取消、作废、晚于开赛、员工）；补偿在 S7
    LEDGER_TYPES.put("payout", List.of("PAYOUT"));
    LEDGER_TYPES.put("fee", List.of("FEE"));
    LEDGER_TYPES.put("refund", List.of("REFUND"));
    // S7：补偿（E13，平台出钱补本应多得的人，AC-39）
    LEDGER_TYPES.put("compensation", List.of("COMPENSATION"));
  }

  private final LedgerApi ledger;
  private final LedgerMapper mapper;
  private final Clock clock;
  private final ObjectProvider<MarketLabels> labels;

  LedgerController(LedgerApi ledger, LedgerMapper mapper, Clock clock, ObjectProvider<MarketLabels> labels) {
    this.ledger = ledger;
    this.mapper = mapper;
    this.clock = clock;
    this.labels = labels;
  }

  @GetMapping("/api/ledger/account")
  AccountView account() {
    AccountSnapshot a = ledger.snapshot(CurrentUser.id());
    return new AccountView("USDT", money(a.balance()), money(a.available()), money(a.betLocked()), money(a.withdrawFrozen()));
  }

  /** 资金流水：可用余额的变动，最新在前。type：{@link #LEDGER_TYPES} 的键（deposit / withdraw / withdrawRefund / bet / payout / fee / refund / compensation），不传或 all 表示全部；range：7d / 30d / 90d / all */
  @GetMapping("/api/ledger/entries")
  Page<LedgerView> entries(@RequestParam(required = false) String type, @RequestParam(defaultValue = "30d") String range,
      @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize) {
    int p = Math.max(page, 1);
    int size = Math.min(Math.max(pageSize, 1), 100);
    boolean all = type == null || type.isBlank() || "all".equals(type);
    if (!all && !LEDGER_TYPES.containsKey(type)) throw new ValidationException(List.of(new ValidationException.Field("type", "invalid")));
    List<String> types = all ? LEDGER_TYPES.values().stream().flatMap(List::stream).toList() : LEDGER_TYPES.get(type);
    LocalDateTime now = LocalDateTime.now(clock);
    LocalDateTime since = switch (range) {
      case "7d" -> now.minusDays(7);
      case "30d" -> now.minusDays(30);
      case "90d" -> now.minusDays(90);
      case "all" -> null;
      default -> throw new ValidationException(List.of(new ValidationException.Field("range", "invalid")));
    };
    long uid = CurrentUser.id();
    List<LedgerMapper.EntryRow> rows = mapper.userLedger(uid, types, since, (p - 1) * size, size);
    List<Long> marketIds = rows.stream().map(r -> r.refMarketId).filter(Objects::nonNull).distinct().toList();
    MarketLabels ml = marketIds.isEmpty() ? null : labels.getIfAvailable();
    Map<Long, MarketLabels.MarketLabel> found = ml == null ? Map.of() : ml.labels(marketIds);
    List<LedgerView> items = rows.stream().map(r -> {
      MarketLabels.MarketLabel l = r.refMarketId == null ? null : found.get(r.refMarketId);
      MarketRef market = l == null ? null : new MarketRef(l.publicNo(), new Title(l.titleEn(), l.titleZhCn(), l.titleZhTw()));
      return new LedgerView(String.valueOf(r.id), ledgerType(r.entryType), money(r.amount), market == null ? ref(r.remark) : "", iso(r.createdAt), market);
    }).toList();
    return new Page<>(items, mapper.countUserLedger(uid, types, since), p, size);
  }

  static String ledgerType(String entryType) {
    for (var e : LEDGER_TYPES.entrySet()) if (e.getValue().contains(entryType)) return e.getKey();
    return entryType.toLowerCase();
  }

  /** 备注最后一段是网络（例如"充值 TRC20"、"充值被链上回滚，冲正 BSC"）；没有时为空 */
  static String ref(String remark) {
    if (remark == null) return "";
    String[] parts = remark.strip().split("\\s+");
    if (parts.length < 2) return "";
    String last = parts[parts.length - 1];
    return last.matches("[A-Z0-9]{3,12}") ? last : "";
  }

  /** 金额：最多 8 位小数，去掉末尾的 0 */
  static String money(BigDecimal v) {
    return v.setScale(8, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
  }

  static String iso(LocalDateTime t) {
    return t == null ? null : t.toInstant(ZoneOffset.UTC).toString();
  }
}
