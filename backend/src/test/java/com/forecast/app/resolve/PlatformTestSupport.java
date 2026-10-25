package com.forecast.app.resolve;

import com.forecast.app.common.error.BizException;
import com.forecast.app.market.api.I18nText;
import com.forecast.app.market.api.MarketAdminApi;
import com.forecast.app.resolve.api.ResolveDeskApi;
import com.forecast.app.resolve.platform.PublicityService;
import com.forecast.app.settle.SettleTestSupport;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 平台裁决测试的公共部分（forecast S7；真实 MySQL 8.4、Redis 7，C37）：沿用结算测试的客户、下注、截止、两种结算事务边界（{@link SettleTestSupport}），
 * 加上平台裁决的市场（时事、争议窗口 2 分钟，AC-31"测试中设 2 分钟"）、提议 / 确认 / 争议的快捷方法、公示期满任务。
 */
public abstract class PlatformTestSupport extends SettleTestSupport {

  @Autowired protected ResolveDeskApi desk;
  @Autowired protected PublicityService publicity;

  static I18nText tx(String s) {
    return new I18nText(s + " en", s + " 简", s + " 繁");
  }

  /** 一个平台裁决的时事市场：1 分钟后截止，事件结束 = 截止 + 2 小时 5 分钟，争议窗口 window 分钟，optionCount 个选项 */
  protected Market platformMarket(int optionCount, int window) {
    return platformMarket("MACRO", optionCount, window);
  }

  protected Market platformMarket(String category, int optionCount, Integer window) {
    Instant now = Instant.now(clock);
    Instant close = now.plus(Duration.ofMinutes(1));
    List<MarketAdminApi.OptionInput> opts = new ArrayList<>();
    for (int i = 1; i <= optionCount; i++) opts.add(new MarketAdminApi.OptionInput(tx("选项" + i), tx("条件" + i)));
    MarketAdminApi.DraftInput in = new MarketAdminApi.DraftInput(category, tx("美联储 11 月议息"), tx("以美联储官网声明为准；无法判定的作废"), "PLATFORM",
        "federalreserve.gov", "reuters.com", null, now.minus(Duration.ofMinutes(1)).toString(), close.toString(), close.plus(Duration.ofMinutes(5)).toString(),
        close.plus(Duration.ofHours(2)).toString(), null, window, null, opts);
    MarketAdminApi.Detail d = admin.createDraft(in, 1);
    admin.publish(Long.parseLong(d.publicNo()), true, 1);
    long id = jdbc.queryForObject("SELECT id FROM market_question WHERE public_no = ?", Long.class, Long.parseLong(d.publicNo()));
    return new Market(id, d.publicNo(), d.options().stream().map(MarketAdminApi.OptionView::optionNo).toList());
  }

  protected long no(Market m) {
    return Long.parseLong(m.no());
  }

  /** 提议内容：结果是第 option 个选项（从 0 数），一个依据链接，理由 */
  protected ResolveDeskApi.ProposalInput result(Market m, int option) {
    return new ResolveDeskApi.ProposalInput(m.options().get(option), null, null, null, List.of("https://www.federalreserve.gov/newsevents/pressreleases.htm"),
        List.of(), "以美联储官网 11 月声明为准");
  }

  protected ResolveDeskApi.ProposalInput result(Market m, int option, Instant actualStartAt) {
    return new ResolveDeskApi.ProposalInput(m.options().get(option), null, null, actualStartAt.toString(), List.of("https://example.org/official"), List.of(),
        "按官方比分");
  }

  protected ResolveDeskApi.ProposalInput voidProposal(String basis) {
    return new ResolveDeskApi.ProposalInput(null, true, basis, null, List.of("https://example.org/notice"), List.of(), "规则有歧义，无法判定");
  }

  /** 提议 + 确认（同一个管理员）：市场进入公示中 */
  protected void publicize(Market m, int option, long adminId) {
    ResolveDeskApi.CaseDetail d = desk.propose(no(m), result(m, option), adminId);
    desk.confirm(no(m), d.summary().proposal().proposalId(), adminId, true);
  }

  /** 时钟拨到争议截止时间（再加 extra），跑一轮公示期满的任务；分批模式把派彩派完 */
  protected Map<Long, String> expire(Market m, Duration extra) {
    Instant deadline = deadline(m);
    Instant now = Instant.now(clock);
    if (now.isBefore(deadline.plus(extra))) advance(Duration.between(now, deadline.plus(extra)));
    Map<Long, String> r = publicity.settleDue();
    if (settleProps.getBatchSize() > 0) settle.drainAll();
    return r;
  }

  protected Instant deadline(Market m) {
    return jdbc.queryForObject("SELECT dispute_deadline_at FROM market_question WHERE id = ?", java.sql.Timestamp.class, m.id()).toLocalDateTime()
        .toInstant(java.time.ZoneOffset.UTC);
  }

  /** 客户提交争议（服务器收到时间 = 现在）；返回 OK 或拒绝的错误码 / 字段错误 */
  protected String tryDispute(long uid, Market m, String claimed, String reason) {
    return tryDispute(uid, m, claimed, reason, Instant.now(clock));
  }

  protected String tryDispute(long uid, Market m, String claimed, String reason, Instant receivedAt) {
    try {
      desk.dispute(no(m), uid, new ResolveDeskApi.DisputeInput(claimed, reason, null), receivedAt);
      return "OK";
    } catch (BizException e) {
      return e.code();
    } catch (com.forecast.app.common.error.ValidationException e) {
      return "VALIDATION:" + e.fields().stream().map(f -> f.field() + "=" + f.code()).toList();
    }
  }

  /** 这个市场的裁决记录种类（按顺序） */
  protected List<String> records(Market m) {
    return jdbc.queryForList("SELECT kind FROM resolve_record WHERE market_id = ? ORDER BY id", String.class, m.id());
  }

  protected String stage(Market m) {
    return jdbc.queryForObject("SELECT stage FROM resolve_case WHERE market_id = ?", String.class, m.id());
  }

  protected int settleRuns(Market m) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM settle_run WHERE market_id = ? AND kind = 'PAYOUT'", Integer.class, m.id());
  }
}
