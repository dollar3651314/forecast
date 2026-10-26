package com.forecast.app.market.pub;

import com.forecast.app.market.api.MarketAdminApi;
import com.forecast.app.market.api.MarketCategory;
import com.forecast.app.market.api.MarketPublicApi;
import com.forecast.app.market.core.ClientViews;
import com.forecast.app.market.core.MarketPublicService;
import com.forecast.app.market.core.MarketService;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 市场的公开快照（forecast S15，{@link MarketPublicApi}）：就是客户端不登录时的市场详情（{@code MarketService.clientDetail(no, null)}），
 * 奖池按"没登录"取，所以没有任何人的持仓；只把自动化程序要用的字段摘出来。
 */
@Component
class MarketSnapshots implements MarketPublicApi {

  private final MarketService markets;
  private final MarketPublicService pub;
  private final ObjectMapper json;
  private final Clock clock;

  MarketSnapshots(MarketService markets, MarketPublicService pub, ObjectMapper json, Clock clock) {
    this.markets = markets;
    this.pub = pub;
    this.json = json;
    this.clock = clock;
  }

  @Override
  public Optional<Snapshot> snapshot(long publicNo) {
    Optional<ClientViews.Detail> d = markets.clientDetail(publicNo, null);
    if (d.isEmpty()) return Optional.empty();
    boolean slate = markets.detail(publicNo).map(MarketAdminApi.Detail::todaySlate).orElse(false);
    return Optional.of(of(d.get(), slate));
  }

  @Override
  public List<Snapshot> slate(Instant from, Instant to) {
    MarketPublicService.Today t = pub.today(from.toString(), to.toString());
    List<Snapshot> out = new ArrayList<>();
    for (ClientViews.Card c : t.items()) snapshot(Long.parseLong(c.publicNo())).ifPresent(out::add);
    return out;
  }

  private Snapshot of(ClientViews.Detail d, boolean slate) {
    JsonNode pool = d.pool() == null ? null : json.valueToTree(d.pool());
    List<Option> options = new ArrayList<>();
    for (ClientViews.OptionDetail o : d.options()) {
      JsonNode p = null;
      if (pool != null) for (JsonNode x : pool.path("options")) if (o.optionNo().equals(x.path("optionNo").asString())) p = x;
      String pct = p == null || !p.hasNonNull("pct") ? "—" : p.path("pct").asString("—").replace("%", "");
      String mult = p == null || !p.hasNonNull("mult") ? null : p.path("mult").asString(null);
      options.add(new Option(o.optionNo(), o.seq(), o.label(), p == null ? "0.00" : p.path("amount").asString("0.00"), p == null ? 0 : p.path("bettors").asInt(0),
          pct.isBlank() ? "—" : pct, mult == null || mult.isBlank() || mult.equals("—") ? null : mult));
    }
    return new Snapshot(Long.parseLong(d.publicNo()), MarketCategory.of(d.category()), d.title(), d.status(), inst(d.openAt()), inst(d.closeAt()),
        inst(d.eventStartAt()), inst(d.eventEndAt()), slate, d.feeRate(), pool == null ? "0.00" : pool.path("total").asString("0.00"),
        pool == null ? 0 : pool.path("bettors").asInt(0), options, d.winningOptionNo(), d.sourcePrimary(), clock.instant());
  }

  private static Instant inst(String iso) {
    return iso == null || iso.isBlank() ? null : Instant.parse(iso);
  }
}
