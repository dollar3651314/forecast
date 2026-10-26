package com.forecast.app.content;

import static org.assertj.core.api.Assertions.assertThat;

import com.forecast.app.content.check.Hant;
import com.forecast.app.content.check.NumberCheck;
import com.forecast.app.content.check.PiiGuard;
import com.forecast.app.content.check.Words;
import com.forecast.app.content.page.BtcStats;
import com.forecast.app.resolve.api.MarketDataApi;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 自动化程序的检查（forecast S15，不连数据库）：数字核对（AC-107）、词表匹配（AC-103、AC-108）、发给 Gemini 前的个人数据检查（AC-116）、
 * 繁体的固定规则转换（EX-9，和 OpenCC 的结果一致）、BTC 每小时涨跌统计（AC-96：和手算一致）。
 */
class ContentChecksTest {

  static Set<BigDecimal> refs(String... values) {
    return NumberCheck.numbersIn(List.of(values));
  }

  @Test
  void 数字核对_找得到的不标_四舍五入和向下取整都算_找不到的标出位置() {
    Set<BigDecimal> known = refs("58.33%", "1,234.56", "×1.73", "2026-10-25 13:00 UTC", "过去 24 小时");
    assertThat(NumberCheck.check("两边比例 58.3% 和 58%，奖池 1,234.56 USDT，约 1234.5 USDT，赔率 ×1.73", known)).isEmpty();
    assertThat(NumberCheck.check("截止 2026-10-25 13:00 UTC，过去 24 小时", known)).isEmpty();
    // AC-107 的例子：把 58% 写成 85%
    List<NumberCheck.Hit> hits = NumberCheck.check("看涨的人占 85%。", known);
    assertThat(hits).hasSize(1);
    assertThat(hits.get(0).text()).isEqualTo("85");
    assertThat("看涨的人占 85%。".substring(hits.get(0).start(), hits.get(0).end())).isEqualTo("85");
    // 不能自己算新数字（差值）
    assertThat(NumberCheck.check("两边相差 16.66 个百分点", refs("58.33", "41.67"))).extracting(NumberCheck.Hit::text).containsExactly("16.66");
    // 紧跟在字母后面的数字（U19、ERC20）不算；行首的列表序号不算
    assertThat(NumberCheck.check("U19 联赛，充值走 ERC20", known)).isEmpty();
    assertThat(NumberCheck.check("1. 第一段\n2、第二段", known)).isEmpty();
  }

  @Test
  void 词表_中文按包含_英文按整词_不分大小写() {
    assertThat(Words.find("这场稳赚不赔", List.of("稳赚", "保本"))).containsExactly("稳赚");
    assertThat(Words.find("Golden State Warriors vs Lakers", List.of("war"))).isEmpty();
    assertThat(Words.find("No WAR here", List.of("war"))).containsExactly("war");
    assertThat(Words.find("It is Risk-Free!", List.of("risk-free"))).containsExactly("risk-free");
    assertThat(Words.find("曼城 U19 vs 利物浦 U19", List.of("U19"))).containsExactly("U19");
  }

  @Test
  void 发给Gemini之前_邮箱地址key和IP拦下_公开数据放行() {
    assertThat(PiiGuard.scan("曼城 vs 利物浦：奖池 120.00 USDT，3 人下注，截止 2026-10-25 13:00 UTC，Binance BTC/USDT 收盘 67,000.5")).isEmpty();
    assertThat(PiiGuard.scan("联系 alice@example.com")).containsExactly("email");
    assertThat(PiiGuard.scan("充值地址 0x52908400098527886E0F7030069857D2E4169EE7")).contains("evmAddress");
    assertThat(PiiGuard.scan("TRON TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t")).contains("tronAddress");
    // 拼出来的假 key（仓库里不放像真 key 的字符串，gitleaks）
    assertThat(PiiGuard.scan("key " + "AI" + "za" + "x".repeat(35))).contains("apiKey");
    assertThat(PiiGuard.scan("登录 IP 203.0.113.7")).containsExactly("ip");
    assertThat(PiiGuard.scan("password=abc")).contains("secret");
  }

  @Test
  void 繁体按固定规则转换_和OpenCC一致() {
    // 期望值由 opencc-js 1.0.5（cn → tw）生成
    assertThat(Hant.convert("过去 7 天里，上涨的小时有 112 个，占 66.7%。")).isEqualTo("過去 7 天裡，上漲的小時有 112 個，佔 66.7%。");
    assertThat(Hant.convert("本页数据来自币安现货 BTC/USDT 的 1 小时 K 线，查询时间 2026-10-25 12:00 UTC。"))
        .isEqualTo("本頁數據來自幣安現貨 BTC/USDT 的 1 小時 K 線，查詢時間 2026-10-25 12:00 UTC。");
    assertThat(Hant.convert("头发、面条、干杯、后来、里面")).isEqualTo("頭髮、麵條、乾杯、後來、裡面");
    assertThat(Hant.convert("只摆数据，不建议押哪边。")).isEqualTo("只擺數據，不建議押哪邊。");
  }

  @Test
  void BTC统计_和手算一致_收盘等于开盘算涨() {
    // 48 根：第 i 根开盘 100，i % 3 == 0 收 99（跌），i % 3 == 1 收 100（相等算涨），i % 3 == 2 收 101（涨）
    Instant start = Instant.parse("2026-10-12T00:00:00Z");
    List<MarketDataApi.Bar> bars = new ArrayList<>();
    for (int i = 0; i < 48; i++) {
      BigDecimal close = BigDecimal.valueOf(i % 3 == 0 ? 99 : i % 3 == 1 ? 100 : 101);
      bars.add(new MarketDataApi.Bar(start.plusSeconds(3600L * i), BigDecimal.valueOf(100), close));
    }
    BtcStats s = BtcStats.of(new MarketDataApi.Hourly("BTCUSDT", "Binance BTC/USDT", true, bars, null, Instant.parse("2026-10-14T00:01:00Z"), "x"));
    assertThat(s.bars()).isEqualTo(48);
    assertThat(s.up7d()).isEqualTo(32);
    assertThat(s.down7d()).isEqualTo(16);
    assertThat(s.up7dPct()).isEqualTo("66.7");
    assertThat(s.up24()).isEqualTo(16);
    assertThat(s.up24Pct()).isEqualTo("66.7");
    // 最后一根（i = 47，47 % 3 == 2）收 101；24 小时前第一根（i = 24）开 100 → +1.00%
    assertThat(s.lastClose()).isEqualTo("101");
    assertThat(s.change24Pct()).isEqualTo("+1.00");
    // 按 UTC 小时：00:00 是 i = 0、24（都 % 3 == 0 → 跌）
    assertThat(s.byHour().get(0).up()).isZero();
    assertThat(s.byHour().get(0).total()).isEqualTo(2);
    assertThat(s.from()).isEqualTo("2026-10-12T00:00:00Z");
    assertThat(s.to()).isEqualTo("2026-10-14T00:00:00Z");
  }
}
