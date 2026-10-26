package com.forecast.app.growth.board;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.forecast.app.growth.telegram.TelegramChannels;
import com.forecast.app.growth.waitlist.WaitlistService;
import com.forecast.app.market.api.PublicMarketsApi;
import com.forecast.app.resolve.api.MarketDataApi;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 整合 4（S16 开发记录第 7 节）：数据页看板的 {@code polymarket.enabled} 跟 S15 的 Polymarket 数据开关（{@code FC_SOURCE_POLYMARKET_ENABLED}），
 * 只问开关、不带 slug；开关出错时按"关"处理（页面不显示这一块，不像出了故障）。
 */
class BoardPolymarketSwitchTest {

  private final MarketDataApi data = mock(MarketDataApi.class);

  private BoardService board() {
    BtcBoard btc = mock(BtcBoard.class);
    PublicMarketsApi markets = mock(PublicMarketsApi.class);
    WaitlistService waitlist = mock(WaitlistService.class);
    TelegramChannels telegram = mock(TelegramChannels.class);
    when(waitlist.launched()).thenReturn(false);
    when(telegram.links()).thenReturn(new TelegramChannels.Links(null, null, null));
    return new BoardService(btc, markets, waitlist, telegram, data, Clock.fixed(Instant.parse("2026-10-26T00:00:00Z"), ZoneOffset.UTC), true);
  }

  private static MarketDataApi.PolymarketQuote quote(boolean enabled) {
    return new MarketDataApi.PolymarketQuote(enabled, false, null, null, List.of(), "x", Instant.EPOCH);
  }

  @Test
  void 开关关着_看板也是关() {
    when(data.polymarket(any())).thenReturn(quote(false));
    assertThat(board().board().polymarket().enabled()).isFalse();
    verify(data, never()).polymarket(any(String.class)); // 只用 null 问开关
  }

  @Test
  void 开关打开_看板跟着打开() {
    when(data.polymarket(any())).thenReturn(quote(true));
    assertThat(board().board().polymarket().enabled()).isTrue();
  }

  @Test
  void 开关出错_按关处理() {
    when(data.polymarket(any())).thenThrow(new IllegalStateException("boom"));
    assertThat(board().board().polymarket().enabled()).isFalse();
  }
}
