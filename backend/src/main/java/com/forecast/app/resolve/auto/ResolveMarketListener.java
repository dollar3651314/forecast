package com.forecast.app.resolve.auto;

import com.forecast.app.market.api.MarketApi;
import com.forecast.app.market.api.MarketCategory;
import com.forecast.app.market.api.MarketListener;
import com.forecast.app.market.api.MarketStatus;
import com.forecast.app.market.api.ResolutionMode;
import com.forecast.app.resolve.platform.CaseBook;
import com.forecast.app.resolve.source.ResolveProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/**
 * resolve 对 market 的 {@link MarketListener} 的实现（forecast S6）：加密、自动裁决的市场进入"待裁决"时，在 market 的同一个事务里建自动裁决的进度行
 * （第一次取数 = 窗口结束 + 1 分钟，转人工 = 再加 30 分钟）。主键是市场 id，重复调用没有效果（INSERT IGNORE，项目地图约定 34）。
 * <p>自动裁决参数（{@code source_ref}）不全的（例如手工建的加密市场没写币种）：直接建成"转人工"、写 AUTO_FAILED，事务提交后邮件告警——不让它停在待裁决没人管。
 * <p>forecast S7：其他市场（平台裁决的、体育在 S8 之前）进入待裁决时建平台裁决的进度行（{@link CaseBook#open}），转人工的加密市场也建；
 * 每次状态跳转同步进度行的阶段（公示中、争议中、结束）。
 */
@Component
class ResolveMarketListener implements MarketListener {

  private static final Logger log = LoggerFactory.getLogger(ResolveMarketListener.class);
  static final Set<MarketCategory> CRYPTO = Set.of(MarketCategory.CRYPTO_15M, MarketCategory.CRYPTO_1H);

  private final ResolveMapper mapper;
  private final MarketApi markets;
  private final ResolveProperties props;
  private final CryptoResolver resolver;
  private final CaseBook cases;
  private final Clock clock;
  private final ObjectMapper json;

  ResolveMarketListener(ResolveMapper mapper, MarketApi markets, ResolveProperties props, CryptoResolver resolver, CaseBook cases, Clock clock,
      ObjectMapper json) {
    this.mapper = mapper;
    this.markets = markets;
    this.props = props;
    this.resolver = resolver;
    this.cases = cases;
    this.clock = clock;
    this.json = json;
  }

  @Override
  public void onStatusChanged(long marketId, MarketStatus from, MarketStatus to) {
    cases.sync(marketId, to);
    if (to != MarketStatus.PENDING_RESOLUTION) return;
    MarketApi.AutoRef m = markets.autoRef(marketId).orElse(null);
    if (m == null) return;
    if (!CRYPTO.contains(m.category()) || m.resolutionMode() != ResolutionMode.AUTO) {
      // 平台裁决的市场；自动裁决的体育市场（S8 之前没有自动裁决）也先进裁决台
      cases.open(marketId, m.resolutionMode() == ResolutionMode.PLATFORM ? CaseBook.PLATFORM : CaseBook.NO_AUTO, m.category());
      return;
    }
    LocalDateTime now = LocalDateTime.now(clock.withZone(ZoneOffset.UTC)).truncatedTo(ChronoUnit.MILLIS);
    Instant first = m.eventEndAt().plus(props.getFirstDelay());
    ResolveRows.Task t = new ResolveRows.Task();
    t.marketId = marketId;
    Object symbol = m.sourceRef() == null ? null : m.sourceRef().get("symbol");
    t.symbol = symbol instanceof String s && s.matches("[A-Z0-9]{2,12}USDT") ? s : "";
    t.windowStart = ldt(m.eventStartAt());
    t.windowEnd = ldt(m.eventEndAt());
    t.nextAttemptAt = ldt(first);
    t.deadlineAt = ldt(first.plus(props.getGiveUpAfter()));
    t.createdAt = now;
    t.updatedAt = now;
    boolean ok = !t.symbol.isEmpty() && CryptoResolver.optionOf(m, "UP") != null && CryptoResolver.optionOf(m, "DOWN") != null
        && "CRYPTO_UPDOWN".equals(m.sourceRef().get("kind"));
    if (ok) {
      t.status = "PENDING";
      mapper.insertTaskIgnore(t);
      return;
    }
    // 自动裁决参数不全：直接转人工
    String reason = "市场的自动裁决参数（币种、涨 / 跌对应的选项）不全，不能自动裁决";
    t.status = "MANUAL";
    t.lastReason = reason;
    if (mapper.insertTaskIgnore(t) != 1) return;
    cases.open(marketId, CaseBook.AUTO_FAILED, m.category());
    ResolveRows.Record r = new ResolveRows.Record();
    r.marketId = marketId;
    r.kind = CryptoResolver.KIND_FAILED;
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("reason", reason);
    detail.put("sourceRef", m.sourceRef());
    r.detail = json.writeValueAsString(detail);
    r.note = reason;
    r.actor = CryptoResolver.AUTO;
    r.createdAt = now;
    mapper.insertRecord(r);
    log.warn("市场 {} 的自动裁决参数不全，直接转人工", m.publicNo());
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
      @Override
      public void afterCommit() {
        resolver.alert(m, reason);
      }
    });
  }

  private static LocalDateTime ldt(Instant i) {
    return LocalDateTime.ofInstant(i.truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
  }
}
