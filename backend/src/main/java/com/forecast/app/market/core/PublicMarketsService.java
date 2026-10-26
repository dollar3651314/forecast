package com.forecast.app.market.core;

import com.forecast.app.market.api.PublicMarketsApi;
import com.forecast.app.market.card.CardModel;
import com.forecast.app.market.card.CardService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link PublicMarketsApi} 的实现（forecast S16）：数字和不登录看到的列表、今日场次（{@link MarketPublicService#today}）、开奖卡（S14 {@link CardService}）
 * 同一份，只是换成推广模块好用的结构。没有个人数据（奖池按"没登录"取）。
 */
@Service
class PublicMarketsService implements PublicMarketsApi {

  private final MarketMapper mapper;
  private final MarketService markets;
  private final MarketPublicService pub;
  private final CardService cards;
  private final ObjectMapper json;
  private final Clock clock;

  PublicMarketsService(MarketMapper mapper, MarketService markets, MarketPublicService pub, CardService cards, ObjectMapper json, Clock clock) {
    this.mapper = mapper;
    this.markets = markets;
    this.pub = pub;
    this.cards = cards;
    this.json = json;
    this.clock = clock;
  }

  @Override
  @Transactional(readOnly = true)
  public Slate todaySlate() {
    MarketPublicService.Today t = pub.today(null, null);
    return new Slate(t.items().stream().map(this::stat).toList(), t.next() == null ? null : stat(t.next()));
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<MarketStat> currentAutogen(String symbol) {
    if (symbol == null || !symbol.matches("[A-Z0-9]{2,16}")) return Optional.empty();
    MarketRows.Market m = mapper.currentAutogen(symbol, LocalDateTime.now(clock.withZone(ZoneOffset.UTC)));
    if (m == null) return Optional.empty();
    return Optional.of(stat(markets.cards(List.of(m)).get(0)));
  }

  @Override
  @Transactional(readOnly = true)
  public List<Ended> endedSlateSince(Instant since, int limit) {
    List<Ended> out = new ArrayList<>();
    for (MarketRows.Market m : mapper.endedSlateSince(LocalDateTime.ofInstant(since, ZoneOffset.UTC), Math.max(1, Math.min(limit, 500)))) {
      out.add(new Ended(m.publicNo, MarketService.status(m).api(), m.endedAt == null ? null : m.endedAt.toInstant(ZoneOffset.UTC)));
    }
    return out;
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<Long> latestEnded() {
    return Optional.ofNullable(mapper.latestEndedNo());
  }

  @Override
  public Optional<PostCard> resultCard(long publicNo, String lang) {
    Optional<CardModel> m = cards.publicCard("result", publicNo, lang);
    if (m.isEmpty()) return Optional.empty();
    CardService.Image img = cards.image(m.get());
    return Optional.of(new PostCard("result", m.get().lang(), img.png(), m.get().text(), m.get().title()));
  }

  @Override
  public String cardTemplateFingerprint(String kind, String lang) {
    return cards.templateFingerprint(kind, lang);
  }

  // ---------------------------------------------------------------------

  private MarketStat stat(ClientViews.Card c) {
    JsonNode pool = c.pool() == null ? null : json.valueToTree(c.pool());
    List<OptionStat> options = new ArrayList<>();
    for (ClientViews.OptionCard o : c.options()) {
      JsonNode op = option(pool, o.optionNo());
      options.add(new OptionStat(o.optionNo(), o.label(), op == null ? 0 : op.path("bettors").asInt(0), op == null ? "0.00" : op.path("amount").asString("0.00"),
          pct(op)));
    }
    Instant openAt = c.openAt() == null ? null : Instant.parse(c.openAt());
    boolean upcoming = "listed".equals(c.status()) && openAt != null && openAt.isAfter(clock.instant());
    return new MarketStat(Long.parseLong(c.publicNo()), c.category(), c.title(), c.status(), upcoming, openAt, instant(c.closeAt()), instant(c.eventStartAt()),
        pool == null ? "0.00" : pool.path("total").asString("0.00"), pool == null ? 0 : pool.path("bettors").asInt(0), c.feeRate(), options);
  }

  private static Instant instant(String s) {
    return s == null ? null : Instant.parse(s);
  }

  private static JsonNode option(JsonNode pool, String optionNo) {
    if (pool == null) return null;
    for (JsonNode o : pool.path("options")) if (optionNo.equals(o.path("optionNo").asString())) return o;
    return null;
  }

  /** 和市场详情、搜索引擎版本同一个概率（详情用的 1 位小数） */
  private static String pct(JsonNode o) {
    if (o == null || !o.hasNonNull("pct")) return "—";
    String p = o.path("pct").asString();
    return p.isBlank() || p.equals("—") ? "—" : p.endsWith("%") ? p : p + "%";
  }
}
