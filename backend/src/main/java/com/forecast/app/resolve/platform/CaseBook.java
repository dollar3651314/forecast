package com.forecast.app.resolve.platform;

import com.forecast.app.market.api.MarketCategory;
import com.forecast.app.market.api.MarketStatus;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Component;

/**
 * 平台裁决的进度行（{@code resolve_case}，forecast S7）：建、跟着市场状态同步阶段。都在调用方的事务里（market 的状态跳转事务、自动裁决转人工的事务）。
 * <ul>
 *   <li>{@link #open}：市场进入待裁决、且不是加密自动裁决在处理的（平台裁决、体育在 S8 之前）；或加密自动裁决 30 分钟后转人工。
 *       提议时限从现在算（体育、加密转人工的"进入裁决台后 2 小时"；时事进入待裁决就是事件结束，"事件结束后 24 小时"）。</li>
 *   <li>{@link #sync}：市场每次状态跳转（公示中、争议中、已结算、已作废）同步阶段；进入终点时清掉待确认的提议和等待，没有结论的争议标成 CLOSED。</li>
 * </ul>
 */
@Component
public class CaseBook {

  public static final String PLATFORM = "PLATFORM";
  public static final String AUTO_FAILED = "AUTO_FAILED";
  public static final String NO_AUTO = "NO_AUTO";

  private final PlatformMapper mapper;
  private final PlatformParams params;
  private final Clock clock;

  CaseBook(PlatformMapper mapper, PlatformParams params, Clock clock) {
    this.mapper = mapper;
    this.params = params;
    this.clock = clock;
  }

  LocalDateTime now() {
    return LocalDateTime.now(clock.withZone(ZoneOffset.UTC)).truncatedTo(ChronoUnit.MILLIS);
  }

  /** 建进度行（重复调用没有效果）；返回是否新建 */
  public boolean open(long marketId, String origin, MarketCategory category) {
    LocalDateTime now = now();
    PlatformRows.Case c = new PlatformRows.Case();
    c.marketId = marketId;
    c.origin = origin;
    c.stage = "OPEN";
    c.proposeDueAt = now.plus(params.proposeWindow(category));
    c.createdAt = now;
    c.updatedAt = now;
    return mapper.insertCaseIgnore(c) == 1;
  }

  /** 市场状态跳转后同步阶段（没有进度行的市场什么都不做） */
  public void sync(long marketId, MarketStatus to) {
    String stage = switch (to) {
      case PENDING_RESOLUTION -> "OPEN";
      case PUBLICIZING -> "PUBLICIZING";
      case DISPUTED -> "DISPUTED";
      case SETTLED, VOIDED, CANCELLED -> "CLOSED";
      default -> null;
    };
    if (stage == null) return;
    LocalDateTime now = now();
    if (mapper.syncStage(marketId, stage, now) == 1 && "CLOSED".equals(stage)) mapper.decideDisputes(marketId, "CLOSED", null, null, now);
  }
}
