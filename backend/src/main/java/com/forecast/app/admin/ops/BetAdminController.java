package com.forecast.app.admin.ops;

import com.forecast.app.account.api.CustomerAdminApi;
import com.forecast.app.admin.security.Permission;
import com.forecast.app.admin.security.RequiresPermission;
import com.forecast.app.pool.api.PoolAdminApi;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后台查看某个市场的下注明细（forecast S4，需求说明书第 2.2 节 {@code bet:view}；页面在原型确认后做，S9）。只读，不写审计。
 * 业务在 pool 模块（{@link PoolAdminApi}）；这里补上客户的邮箱和姓名（account 模块）。
 */
@RestController
class BetAdminController {

  /** 一笔下注 + 客户（邮箱、姓名；客户不存在时为 null） */
  record BetView(PoolAdminApi.BetRow bet, String email, String name) {}

  record MarketBetsView(String marketNo, String status, String total, String activeTotal, int bettors, boolean frozen, List<PoolAdminApi.OptionRow> options,
      PoolAdminApi.Page<BetView> bets) {}

  private final PoolAdminApi pool;
  private final CustomerAdminApi customers;

  BetAdminController(PoolAdminApi pool, CustomerAdminApi customers) {
    this.pool = pool;
    this.customers = customers;
  }

  /**
   * 下注明细：奖池汇总（各选项的奖池、人数、截止时定格的值；有效下注合计应当等于奖池合计）+ 一页下注（按收到时间倒序，精确到毫秒）。
   * 筛选：optionNo（选项编号）、userId（客户号）、status（active / refunded）。
   */
  @RequiresPermission(Permission.BET_VIEW)
  @GetMapping("/api/admin/markets/{publicNo}/bets")
  MarketBetsView bets(@PathVariable String publicNo, @RequestParam(required = false) String optionNo, @RequestParam(required = false) Long userId,
      @RequestParam(required = false) String status, @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "50") int pageSize) {
    PoolAdminApi.MarketBets b = pool.marketBets(MarketOps.no(publicNo), optionNo, userId, status, page, pageSize);
    Map<Long, CustomerAdminApi.Customer> who = b.bets().items().isEmpty() ? Map.of()
        : customers.customers(b.bets().items().stream().map(PoolAdminApi.BetRow::userId).distinct().toList());
    List<BetView> items = b.bets().items().stream().map(r -> {
      CustomerAdminApi.Customer c = who.get(r.userId());
      return new BetView(r, c == null ? null : c.email(), c == null ? null : c.name());
    }).toList();
    return new MarketBetsView(b.marketNo(), b.status(), b.total(), b.activeTotal(), b.bettors(), b.frozen(), b.options(),
        new PoolAdminApi.Page<>(items, b.bets().total(), b.bets().page(), b.bets().pageSize()));
  }
}
