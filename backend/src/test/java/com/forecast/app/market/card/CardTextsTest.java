package com.forecast.app.market.card;

import static org.assertj.core.api.Assertions.assertThat;

import com.forecast.app.market.pub.PublicContent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 分享卡片的文字模板和画图（forecast S14，不连数据库）：{@code content/cards.json} 三种语言的键一致、没有禁用词（F-12）、变量只用程序会填的那些、
 * 模板里不写数字（数字只能来自系统）；繁体里没有常见的简体字；折行和截断；按字符选字体（有中文字体时中文用中文字体）。
 */
class CardTextsTest {

  static final List<String> VARS = List.of("{bettors}", "{amount}", "{mult}", "{n}", "{pool}", "{fee}", "{utc}", "{local}", "{time}", "{mine}", "{other}",
      "{minutes}", "{result}", "{payout}", "{dust}", "{start}", "{end}", "{source}", "{home}", "{away}", "{homeScore}", "{awayScore}", "{by}", "{reason}",
      "{refund}", "{option}", "{week}", "{from}", "{to}", "{reasons}");

  static JsonNode root() throws Exception {
    try (InputStream in = CardTextsTest.class.getResourceAsStream("/content/cards.json")) {
      return new ObjectMapper().readTree(in);
    }
  }

  static void paths(JsonNode n, String prefix, List<String> out) {
    if (n.isObject()) n.properties().forEach(e -> paths(e.getValue(), prefix + "." + e.getKey(), out));
    else out.add(prefix);
  }

  static void texts(JsonNode n, List<String> out) {
    if (n.isObject()) n.properties().forEach(e -> texts(e.getValue(), out));
    else if (n.isString()) out.add(n.asString());
  }

  @Test
  void 三种语言键一致_没有禁用词_变量都认识_模板里没有数字() throws Exception {
    JsonNode r = root();
    List<String> en = new ArrayList<>();
    paths(r.get("en"), "", en);
    for (String l : PublicContent.LANGS) {
      List<String> p = new ArrayList<>();
      paths(r.get(l), "", p);
      assertThat(p).as(l).isEqualTo(en);
      List<String> t = new ArrayList<>();
      texts(r.get(l), t);
      List<String> rules = new ArrayList<>();
      texts(r.get(l).get("reasons"), rules);
      for (String s : t) {
        for (String b : S14CardsTest.BANNED) assertThat(s.toLowerCase(Locale.ROOT)).as(l + "：" + s).doesNotContain(b.toLowerCase(Locale.ROOT));
        String rest = s;
        for (String v : VARS) rest = rest.replace(v, "");
        assertThat(rest).as("变量以外的大括号：" + s).doesNotContain("{").doesNotContain("}");
        // 数字只能来自系统（市场、费率、人数都是变量）；"0 人"是规则原文（AC-87），"UTC+8"、"15 分钟"等是名称
        String noNames = rest.replace("UTC+8", "").replace("15 min", "").replace("1 hour", "").replace("15 分钟", "").replace("1 小时", "")
            .replace("15 分鐘", "").replace("1 小時", "").replace("0 bettors", "").replace("0 人", "");
        // 作废原因是固定规则的说法（E6 / E7 的 48 小时，和客户端 md.res.reasons 一样），可以有数字
        if (!rules.contains(s)) assertThat(noNames).as("模板里写了数字：" + s).doesNotContainPattern("[0-9]");
      }
      if (l.equals("zh-TW")) {
        for (String s : t) {
          for (String simp : List.of("币", "账", "结", "费", "这", "说", "们", "现", "额", "单", "时", "赔", "奖", "数", "据", "项", "选", "开", "将", "预")) {
            assertThat(s).as("繁体里有简体字 " + simp + "：" + s).doesNotContain(simp);
          }
        }
      }
    }
  }

  @Test
  void 折行_中文按字_英文按词_超过行数截断() {
    CardPainter p = new CardPainter(new CardFonts("/usr/share/fonts", false));
    assertThat(CardPainter.tokens("BTC 涨还是跌：13:00")).containsExactly("BTC ", "涨", "还", "是", "跌", "：", "13:00");
    List<String> zh = p.wrap("这是一个非常非常长的题目，用来检查卡片的折行和截断，".repeat(6), 36, true, 1072, 2);
    assertThat(zh).hasSize(2);
    assertThat(zh.get(1)).endsWith("…");
    List<String> en = p.wrap("Manchester City vs Liverpool: total goals over 2.5 in regular time ".repeat(3), 36, true, 1072, 3);
    assertThat(en.size()).isLessThanOrEqualTo(3);
    for (String line : en) assertThat(p.width(line, 36, true)).isLessThanOrEqualTo(1072);
    assertThat(p.fit("短", 20, false, 500)).isEqualTo("短");
  }

  @Test
  void 有中文字体时按字符选字体_画出来是1200x630() throws Exception {
    CardFonts f = new CardFonts("/usr/share/fonts,/usr/local/share/fonts", true);
    List<CardFonts.Run> runs = f.runs("BTC 涨 ×2.24", 20, true);
    if (f.hasCjk()) {
      assertThat(runs).hasSize(3);
      assertThat(runs.get(1).text()).isEqualTo("涨");
      assertThat(runs.get(1).font().canDisplay('涨')).isTrue();
      assertThat(f.evaluate()).singleElement().matches(i -> i.ok() && i.name().equals("cards.font"));
    } else {
      assertThat(f.evaluate()).singleElement().matches(i -> !i.ok());
    }
    assertThat(new CardFonts("/nonexistent", false).evaluate()).isEmpty();
    CardModel m = new CardModel("market", "zh-CN", "Quick Come", "加密 1 小时", "可下注", null, "BTC 涨还是跌：2026-10-26 13:00–14:00 UTC",
        List.of(new CardModel.Row("涨", "58.3%", 0.583, "12 人 · 680.00 USDT · 预计赔率 ×1.73", CardModel.Tone.UP, true),
            new CardModel.Row("跌", "41.7%", 0.417, "8 人 · 520.00 USDT · 预计赔率 ×2.24", CardModel.Tone.DOWN, false)),
        List.of("奖池 1,200.00 USDT · 20 人下注 · 对赢家盈利抽成 5%", "截止下注 2026-10-26 12:59 UTC（北京时间 10-26 20:59）"),
        List.of("概率和预计赔率随奖池变化，已扣抽成，最终以截止时为准", "截止时只有一边有人押，全额退款"), "数据时间 2026-10-26 12:54 UTC", CardModel.Tone.PRIMARY);
    BufferedImage img = ImageIO.read(new ByteArrayInputStream(new CardPainter(f).png(m)));
    assertThat(img.getWidth()).isEqualTo(1200);
    assertThat(img.getHeight()).isEqualTo(630);
    assertThat(m.text()).isEqualTo("""
        BTC 涨还是跌：2026-10-26 13:00–14:00 UTC
        ✓ 涨 58.3% · 12 人 · 680.00 USDT · 预计赔率 ×1.73
        · 跌 41.7% · 8 人 · 520.00 USDT · 预计赔率 ×2.24
        奖池 1,200.00 USDT · 20 人下注 · 对赢家盈利抽成 5%
        截止下注 2026-10-26 12:59 UTC（北京时间 10-26 20:59）
        概率和预计赔率随奖池变化，已扣抽成，最终以截止时为准
        截止时只有一边有人押，全额退款""");
  }

  @Test
  void 金额千分位_概率条长度() {
    assertThat(CardService.money("1234.5")).isEqualTo("1,234.50");
    assertThat(CardService.money("0")).isEqualTo("0.00");
    assertThat(CardService.money("12.349")).isEqualTo("12.34");
    assertThat(CardService.ratio("58.3%")).isEqualTo(0.583);
    assertThat(CardService.ratio("<1%")).isEqualTo(0.005);
    assertThat(CardService.ratio(">99%")).isEqualTo(0.995);
    assertThat(CardService.ratio("—")).isZero();
    assertThat(CardService.pct("58")).isEqualTo("58%");
    assertThat(CardService.newToken()).matches("[A-Za-z0-9_-]{22}");
  }
}
