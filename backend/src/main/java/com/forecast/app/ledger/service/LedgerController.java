package com.forecast.app.ledger.service;

import com.forecast.app.common.error.ValidationException;
import com.forecast.app.common.web.CurrentUser;
import com.forecast.app.ledger.api.AccountSnapshot;
import com.forecast.app.ledger.api.LedgerApi;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 客户端的账户和资金流水接口（需要登录）。复制源的 {@code GET /api/trading/account}、{@code GET /api/trading/ledger}（交易控制器）
 * 删掉差价合约的字段后搬到这里（forecast S1）。字段名和客户端 web/client/src/api/types.ts 一致，金额一律用字符串。
 */
@RestController
class LedgerController {

  /** S1 时 available = balance（没有下注锁定）；S2 起拆成可用余额、下注锁定、提现冻结三项 */
  record AccountView(String currency, String balance, String withdrawFrozen, String available) {}

  /** 资金流水的一条；ref 是相关的网络（例如充值 TRC20），没有时为空字符串 */
  record LedgerView(String id, String type, String amount, String ref, String time) {}

  record Page<T>(List<T> items, long total, int page, int pageSize) {}

  /** 客户端流水类型 → 账本类型（只看余额的变动）。S2 起加下注、派彩、退款、补偿 */
  static final Map<String, List<String>> LEDGER_TYPES = new LinkedHashMap<>();

  static {
    LEDGER_TYPES.put("deposit", List.of("DEPOSIT", "DEPOSIT_REVERSAL"));
    LEDGER_TYPES.put("withdraw", List.of("WITHDRAW_FREEZE"));
    LEDGER_TYPES.put("withdrawRefund", List.of("WITHDRAW_UNFREEZE"));
  }

  private final LedgerApi ledger;
  private final LedgerMapper mapper;
  private final Clock clock;

  LedgerController(LedgerApi ledger, LedgerMapper mapper, Clock clock) {
    this.ledger = ledger;
    this.mapper = mapper;
    this.clock = clock;
  }

  @GetMapping("/api/ledger/account")
  AccountView account() {
    AccountSnapshot a = ledger.snapshot(CurrentUser.id());
    return new AccountView("USDT", money(a.balance()), money(a.withdrawFrozen()), money(a.balance()));
  }

  /** 资金流水：余额的变动，最新在前。type：deposit / withdraw / withdrawRefund，不传或 all 表示全部；range：7d / 30d / 90d / all */
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
    List<LedgerView> items = mapper.userLedger(uid, types, since, (p - 1) * size, size).stream()
        .map(r -> new LedgerView(String.valueOf(r.id), ledgerType(r.entryType), money(r.amount), ref(r.remark), iso(r.createdAt))).toList();
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
