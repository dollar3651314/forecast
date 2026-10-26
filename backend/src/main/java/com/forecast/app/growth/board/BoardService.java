package com.forecast.app.growth.board;

import com.forecast.app.growth.telegram.TelegramChannels;
import com.forecast.app.growth.waitlist.WaitlistService;
import com.forecast.app.market.api.I18nText;
import com.forecast.app.market.api.PublicMarketsApi;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 数据页兼等候页 {@code /data} 的数据（forecast S16，MF-8、MF-17；需求第 5.2 节 P13、AC-95、AC-96）。
 * <ol>
 *   <li><b>BTC</b>：现价、过去 N 小时每小时涨跌的比例（{@link BtcBoard}，币安公开行情，带更新时间）；</li>
 *   <li><b>Polymarket</b>：S15 做数据开关（默认关），这次不接，{@code polymarket.enabled} 一直是 false，页面不显示这一块；</li>
 *   <li><b>我们今天的场次</b>：正式上线（发过上线通知）以后才有——今天（UTC）带"今日场次"标记的市场，两边的人数、概率（和市场详情同一份数字）；
 *       上线前这一块是"即将上线"和留邮箱。"不登录也能看"的开关关掉时这一块不给数据。</li>
 * </ol>
 * 全部是公开数据，没有任何客户的个人信息；不放 X 的数据。
 */
@Service
public class BoardService {

  public record Btc(boolean available, String symbol, String price, int hours, int up, int down, String upPct, String downPct, String from, String to,
      String updatedAt) {}

  public record Option(String optionNo, I18nText label, int bettors, String amount, String pct) {}

  public record Item(String publicNo, String category, I18nText title, String status, boolean upcoming, String openAt, String closeAt, String eventStartAt,
      String poolTotal, int bettors, List<Option> options) {}

  /** available：给不给今天的场次（上线以后、公开浏览打开时） */
  public record Slate(boolean available, List<Item> items, Item next) {}

  public record Polymarket(boolean enabled) {}

  /** 官方 Telegram 频道、机器人的链接（配置了、打开了才有） */
  public record Telegram(String channelZh, String channelEn, String bot) {}

  /** launched：已经正式上线（发过上线通知）；waitlistOpen：等候页还在收邮箱 */
  public record Board(String updatedAt, Btc btc, boolean launched, boolean waitlistOpen, Slate slate, Polymarket polymarket, Telegram telegram) {}

  private final BtcBoard btc;
  private final PublicMarketsApi markets;
  private final WaitlistService waitlist;
  private final TelegramChannels telegram;
  private final Clock clock;
  private final boolean publicBrowse;

  BoardService(BtcBoard btc, PublicMarketsApi markets, WaitlistService waitlist, TelegramChannels telegram, Clock clock,
      @Value("${forecast.public-browse.enabled:true}") boolean publicBrowse) {
    this.btc = btc;
    this.markets = markets;
    this.waitlist = waitlist;
    this.telegram = telegram;
    this.clock = clock;
    this.publicBrowse = publicBrowse;
  }

  public Board board() {
    boolean launched = waitlist.launched();
    Slate slate = new Slate(false, List.of(), null);
    if (launched && publicBrowse) {
      PublicMarketsApi.Slate s = markets.todaySlate();
      slate = new Slate(true, s.items().stream().map(BoardService::item).toList(), s.next() == null ? null : item(s.next()));
    }
    TelegramChannels.Links l = telegram.links();
    return new Board(clock.instant().toString(), btc(), launched, !launched, slate, new Polymarket(false), new Telegram(l.channelZh(), l.channelEn(), l.bot()));
  }

  public Btc btc() {
    BtcBoard.Snapshot s = btc.snapshot();
    if (s == null) return new Btc(false, null, null, 0, 0, 0, null, null, null, null, null);
    return new Btc(true, s.symbol(), s.price(), s.hours(), s.up(), s.down(), s.upPct(), s.downPct(), s.from().toString(), s.to().toString(),
        s.fetchedAt().toString());
  }

  private static Item item(PublicMarketsApi.MarketStat m) {
    return new Item(String.valueOf(m.publicNo()), m.category(), m.title(), m.status(), m.upcoming(), str(m.openAt()), str(m.closeAt()), str(m.eventStartAt()),
        m.poolTotal(), m.bettors(), m.options().stream().map(o -> new Option(o.optionNo(), o.label(), o.bettors(), o.amount(), o.pct())).toList());
  }

  private static String str(Instant i) {
    return i == null ? null : i.toString();
  }
}
