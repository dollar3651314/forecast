package com.forecast.app.market.card;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.forecast.app.market.core.MarketPublicService;
import com.forecast.app.settle.SettleTestSupport;
import jakarta.servlet.http.Cookie;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/**
 * 分享卡片（forecast S14，MF-5；需求说明书 v1.3.2 第 5.2 节"分享卡片和挑战卡"、第 6.3 节、AC-86、AC-87）。真实 MySQL、Redis（C37）。
 * <ul>
 *   <li>市场卡、挑战卡、开奖卡、我押中的开奖卡、封盘倒计时卡、周报卡：图片是 1200 × 630 的 PNG；卡片上的人数、金额、概率、预计赔率和系统数据一致（AC-86）；</li>
 *   <li>挑战卡对面 0 人时照实写（AC-87），只能为自己押过的选项生成；开奖卡金额默认不显示，选择显示才显示（AC-86）；</li>
 *   <li>链接带 src=share、分享人 ref（登录了才有）；卡片和链接里没有客户的个人信息；文字没有禁用词（F-12）；</li>
 *   <li>Open Graph：分享链接的页面头部有卡片图片（og:image、twitter:card summary_large_image），预览语言跟着 cl；robots 放行卡片图片。</li>
 * </ul>
 */
class S14CardsTest extends SettleTestSupport {

  /** 禁用的说法（市场策略 F-12、F-15；和通知模板 S9NotifyFlowTest.BANNED、公开页面 PublicContentTest.BANNED 合起来） */
  static final List<String> BANNED = List.of("稳赚", "必中", "保本", "翻倍", "胜者瓜分", "最后入场", "别错过", "秒出", "全在链上", "链上公开", "私房钱", "恭喜", "穩賺",
      "保證", "勝者瓜分", "最後入場", "別錯過", "guaranteed", "risk-free", "risk free", "sure win", "don't miss", "last chance", "congratulations", "double your");

  @Autowired MarketPublicService pub;
  @Autowired CardService cards;

  MockHttpServletResponse anon(MockHttpServletRequestBuilder b) throws Exception {
    return call(b, null, null);
  }

  JsonNode share(String q, Cookie c) throws Exception {
    MockHttpServletResponse r = call(get("/api/public/share?" + q), c, null);
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    return body(r);
  }

  MockHttpServletResponse create(Cookie c, Map<String, Object> req) throws Exception {
    return call(post("/api/me/share"), c, json.writeValueAsString(req));
  }

  static BufferedImage png(MockHttpServletResponse r) throws Exception {
    assertThat(r.getStatus()).isEqualTo(200);
    assertThat(r.getContentType()).isEqualTo("image/png");
    BufferedImage img = ImageIO.read(new ByteArrayInputStream(r.getContentAsByteArray()));
    assertThat(img).isNotNull();
    assertThat(img.getWidth()).isEqualTo(1200);
    assertThat(img.getHeight()).isEqualTo(630);
    return img;
  }

  /** 图片地址（https://app.forecast.test/api/...）→ 本地请求路径 */
  static String path(String url) {
    return url.substring(url.indexOf("/api/"));
  }

  static String email(long uid, org.springframework.jdbc.core.JdbcTemplate jdbc) {
    return jdbc.queryForObject("SELECT email FROM account_user WHERE id = ?", String.class, uid);
  }

  @Test
  void AC86_市场卡_图片1200x630_文字和系统数字一致_链接带来源_登录了才带分享人_没有个人信息() throws Exception {
    Market m = market(2);
    User a = user("500");
    User b = user("500");
    User c = user("500");
    bet(a.id(), m, 0, "60");
    bet(b.id(), m, 0, "20");
    bet(c.id(), m, 1, "40");
    JsonNode d = getJson("/api/markets/" + m.no(), null);
    JsonNode pool = d.path("pool");
    JsonNode s = share("kind=market&no=" + m.no() + "&lang=zh-CN", null);
    assertThat(s.path("kind").asString()).isEqualTo("market");
    String text = s.path("text").asString();
    assertThat(text).startsWith("曼城 vs 利物浦 简").contains("奖池 120.00 USDT · 3 人下注 · 对赢家盈利抽成 5%").contains("截止下注 ").contains("（北京时间 ");
    for (JsonNode o : pool.path("options")) {
      String pct = o.path("pct").asString();
      assertThat(text).contains(pct.endsWith("%") ? pct : pct + "%").contains(o.path("bettors").asInt() + " 人 · " + o.path("amount").asString() + " USDT · 预计赔率 ×"
          + o.path("mult").asString());
    }
    assertThat(text).contains("概率和预计赔率随奖池变化，已扣抽成，最终以截止时为准").contains("截止时只有一边有人押，全额退款");
    // 链接：src=share、cmp=card-market、cl；没登录没有 ref
    String url = s.path("url").asString();
    assertThat(url).isEqualTo("https://app.forecast.test/m/" + m.no() + "?src=share&cmp=card-market&cl=zh-CN");
    assertThat(s.path("imageUrl").asString()).isEqualTo("https://app.forecast.test/api/public/cards/market/" + m.no() + ".png?lang=zh-CN");
    // 登录了：ref = 这个客户的随机分享码（S15：不再是递增的客户编号；只用于来源统计，MQ-5）
    JsonNode mine = share("kind=market&no=" + m.no() + "&lang=en", a.cookie());
    String code = shareCode(a.id());
    assertThat(code).matches("[a-hj-km-np-z][a-hj-km-np-z2-9]{9}").isNotEqualTo(String.valueOf(a.id()));
    assertThat(mine.path("url").asString()).isEqualTo("https://app.forecast.test/m/" + m.no() + "?src=share&cmp=card-market&ref=" + code + "&cl=en");
    // 同一个客户每次都是同一个码
    assertThat(share("kind=market&no=" + m.no() + "&lang=zh-CN", a.cookie()).path("url").asString()).contains("ref=" + code + "&");
    assertThat(mine.path("text").asString()).contains("Pool 120.00 USDT · 3 bettors").contains("est. odds ×");
    // 图片：三种语言都是 1200 × 630 的 PNG；ETag 一样时 304；不登录也能取
    for (String l : List.of("zh-CN", "zh-TW", "en")) png(anon(get("/api/public/cards/market/" + m.no() + ".png?lang=" + l)));
    MockHttpServletResponse r1 = anon(get(path(s.path("imageUrl").asString())));
    png(r1);
    assertThat(r1.getHeader("ETag")).isNotBlank();
    assertThat(r1.getHeader("Cache-Control")).contains("max-age=60").contains("public");
    assertThat(anon(get(path(s.path("imageUrl").asString())).header("If-None-Match", r1.getHeader("ETag"))).getStatus()).isEqualTo(304);
    // 没有个人信息：文字和链接里没有任何客户的邮箱
    for (User u : List.of(a, b, c)) assertThat(text + url).doesNotContain(email(u.id(), jdbc));
    // 不存在、草稿：404
    assertThat(anon(get("/api/public/cards/market/123.png")).getStatus()).isEqualTo(404);
    assertThat(anon(get("/api/public/share?kind=market&no=123")).getStatus()).isEqualTo(404);
    var draft = admin.createDraft(new com.forecast.app.market.api.MarketAdminApi.DraftInput("MACRO", new com.forecast.app.market.api.I18nText("d", "d", "d"),
        new com.forecast.app.market.api.I18nText("r", "r", "r"), "PLATFORM", "a", "b", null, Instant.now(clock).plusSeconds(600).toString(),
        Instant.now(clock).plusSeconds(3600).toString(), Instant.now(clock).plusSeconds(3700).toString(), Instant.now(clock).plusSeconds(7200).toString(), null,
        120, null, List.of(new com.forecast.app.market.api.MarketAdminApi.OptionInput(new com.forecast.app.market.api.I18nText("y", "y", "y"),
            new com.forecast.app.market.api.I18nText("y", "y", "y")), new com.forecast.app.market.api.MarketAdminApi.OptionInput(
                new com.forecast.app.market.api.I18nText("n", "n", "n"), new com.forecast.app.market.api.I18nText("n", "n", "n")))), 1);
    assertThat(anon(get("/api/public/cards/market/" + draft.publicNo() + ".png")).getStatus()).isEqualTo(404);
  }

  @Test
  void AC87_挑战卡_对面0人照实写_有人以后写对面的预计赔率_只能为自己押过的选项生成_凭证不带个人信息() throws Exception {
    Market m = market(2);
    User a = user("500");
    bet(a.id(), m, 0, "50");
    // 没登录不能生成；没押过的选项不能生成
    assertThat(create(null, Map.of("kind", "challenge", "marketNo", m.no(), "optionNo", m.options().get(0))).getStatus()).isEqualTo(401);
    MockHttpServletResponse bad = create(a.cookie(), Map.of("kind", "challenge", "marketNo", m.no(), "optionNo", m.options().get(1), "lang", "zh-CN"));
    assertThat(bad.getStatus()).isEqualTo(400);
    assertThat(bad.getContentAsString()).contains("CARD_NOT_AVAILABLE");
    MockHttpServletResponse r = create(a.cookie(), Map.of("kind", "challenge", "marketNo", m.no(), "optionNo", m.options().get(0), "lang", "zh-CN"));
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    JsonNode s = body(r);
    assertThat(s.path("kind").asString()).isEqualTo("challenge");
    String text = s.path("text").asString();
    assertThat(text).startsWith("我押了「选项1 简」，谁敢押「选项2 简」？").contains("对面目前 0 人；截止时仍只有一边有人押，全额退款");
    assertThat(text).doesNotContain("当前预计赔率 ×");
    String token = jdbc.queryForObject("SELECT token FROM market_share WHERE user_id = ? AND kind = 'CHALLENGE'", String.class, a.id());
    assertThat(token).matches("[A-Za-z0-9_-]{22}");
    assertThat(s.path("url").asString()).isEqualTo("https://app.forecast.test/m/" + m.no() + "?src=share&cmp=card-challenge&ref=" + shareCode(a.id()) + "&cl=zh-CN&card=" + token);
    assertThat(s.path("imageUrl").asString()).isEqualTo("https://app.forecast.test/api/public/cards/s/" + token + ".png?lang=zh-CN");
    png(anon(get(path(s.path("imageUrl").asString()))));
    // 对面有人押了：写对面当前的预计赔率（和详情的奖池同一个数，需求第 3.4 节）；再分享沿用同一个凭证
    User b = user("500");
    bet(b.id(), m, 1, "30");
    JsonNode s2 = body(create(a.cookie(), Map.of("kind", "challenge", "marketNo", m.no(), "optionNo", m.options().get(0), "lang", "zh-CN")));
    assertThat(s2.path("url").asString()).endsWith("card=" + token);
    JsonNode other = null;
    for (JsonNode o : getJson("/api/markets/" + m.no(), null).path("pool").path("options")) if (o.path("optionNo").asString().equals(m.options().get(1))) other = o;
    assertThat(other).isNotNull();
    assertThat(s2.path("text").asString()).contains("「选项2 简」方当前预计赔率 ×" + other.path("mult").asString() + "（已扣 5% 抽成）").contains("1 人 · 30.00 USDT")
        .contains("1 人 · 50.00 USDT").doesNotContain("对面目前 0 人");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM market_share WHERE user_id = ?", Integer.class, a.id())).isEqualTo(1);
    // 不带个人信息：凭证图片地址、文字里没有邮箱和客户编号以外的东西
    assertThat(s2.path("text").asString() + s2.path("imageUrl").asString()).doesNotContain(email(a.id(), jdbc)).doesNotContain(email(b.id(), jdbc));
    // 英文
    JsonNode en = body(create(a.cookie(), Map.of("kind", "challenge", "marketNo", m.no(), "optionNo", m.options().get(0), "lang", "en")));
    assertThat(en.path("text").asString()).startsWith("I picked \"选项1 en\". Who picks \"选项2 en\"?");
    // 截止以后不能再生成挑战卡（谁敢押已经没有意义），已经生成的图片仍然能看
    closeAndEnd(m);
    assertThat(create(a.cookie(), Map.of("kind", "challenge", "marketNo", m.no(), "optionNo", m.options().get(0))).getStatus()).isEqualTo(400);
    png(anon(get("/api/public/cards/s/" + token + ".png")));
    assertThat(anon(get("/api/public/cards/s/AAAAAAAAAAAAAAAAAAAAAA.png")).getStatus()).isEqualTo(404);
  }

  @Test
  void AC86_开奖卡_数字和开奖记录一致_押中的开奖卡金额默认不显示_选择显示才显示() throws Exception {
    Market m = shortMarket(2);
    User a = user("500");
    User b = user("500");
    bet(a.id(), m, 0, "100");
    bet(b.id(), m, 1, "50");
    closeAndEnd(m);
    settle(m, 0);
    MarketPublicService.Result r = pub.result(Long.parseLong(m.no())).orElseThrow();
    JsonNode st = json.valueToTree(r.settle());
    // 市场已结算：市场卡自动换成开奖卡
    JsonNode s = share("kind=market&no=" + m.no() + "&lang=zh-CN", null);
    assertThat(s.path("kind").asString()).isEqualTo("result");
    assertThat(s.path("imageUrl").asString()).contains("/api/public/cards/result/" + m.no() + ".png");
    String text = s.path("text").asString();
    assertThat(text).startsWith("结果：选项1 简").contains("奖池 " + st.path("poolTotal").asString() + " USDT · 派彩合计 " + st.path("payoutTotal").asString()
        + " USDT · 抽成 " + st.path("feeTotal").asString() + " USDT · 尾差 " + st.path("dust").asString() + " USDT").contains("下注人数 " + r.bettors())
        .doesNotContain("我押中了");
    MockHttpServletResponse img = anon(get(path(s.path("imageUrl").asString())));
    png(img);
    assertThat(img.getHeader("Cache-Control")).contains("max-age=3600");
    // 押中的客户：默认不显示金额
    JsonNode w = body(create(a.cookie(), Map.of("kind", "win", "marketNo", m.no(), "lang", "zh-CN")));
    assertThat(w.path("kind").asString()).isEqualTo("win");
    assertThat(w.path("text").asString()).startsWith("我押中了「选项1 简」").doesNotContain("我的派彩");
    assertThat(w.path("url").asString()).contains("cmp=card-win&ref=" + shareCode(a.id()));
    png(anon(get(path(w.path("imageUrl").asString()))));
    // 选择显示：显示本人的派彩（和详情里本人的结算结果一致）；换了一个凭证
    String payout = getJson("/api/markets/" + m.no(), a.cookie()).path("settle").path("mine").path("payout").asString();
    JsonNode shown = body(create(a.cookie(), Map.of("kind", "win", "marketNo", m.no(), "showAmount", true, "lang", "zh-CN")));
    assertThat(shown.path("text").asString()).contains("我的派彩 " + CardService.money(payout) + " USDT（已扣抽成）");
    assertThat(shown.path("url").asString()).isNotEqualTo(w.path("url").asString());
    // 没押中的不能生成押中的开奖卡
    MockHttpServletResponse lose = create(b.cookie(), Map.of("kind", "win", "marketNo", m.no()));
    assertThat(lose.getStatus()).isEqualTo(400);
    // 结束以后不能生成挑战卡
    assertThat(create(a.cookie(), Map.of("kind", "challenge", "marketNo", m.no(), "optionNo", m.options().get(0))).getStatus()).isEqualTo(400);
  }

  @Test
  void 取消的市场_开奖卡写原因和全额退款() throws Exception {
    Market m = market(2);
    User a = user("500");
    User b = user("500");
    bet(a.id(), m, 0, "10");
    bet(b.id(), m, 1, "20");
    admin.cancel(Long.parseLong(m.no()), 1, "赛事取消（测试）");
    if (settleProps.getBatchSize() > 0) settle.drainAll();
    MarketPublicService.Result r = pub.result(Long.parseLong(m.no())).orElseThrow();
    String text = share("kind=market&no=" + m.no() + "&lang=zh-CN", null).path("text").asString();
    assertThat(text).startsWith("已取消").contains("原因：赛事取消（测试）").contains("已全额退款 30.00 USDT，不收抽成").contains("下注人数 " + r.bettors())
        .contains("作废、取消的市场全额退款，不收抽成");
    assertThat(r.bettors()).isEqualTo(2);
    png(anon(get("/api/public/cards/result/" + m.no() + ".png?lang=zh-TW")));
  }

  @Test
  void 封盘倒计时卡_距截止几分钟_一边0人照实写_截止以后没有() throws Exception {
    Market m = market(2, Instant.now(clock).plus(Duration.ofMinutes(5)).plusSeconds(30), null);
    User a = user("500");
    bet(a.id(), m, 0, "68");
    JsonNode s = share("kind=countdown&no=" + m.no() + "&lang=zh-CN", null);
    assertThat(s.path("kind").asString()).isEqualTo("countdown");
    String text = s.path("text").asString();
    assertThat(text).startsWith("距截止 5 分钟").contains("1 人 · 68.00 USDT").contains("「选项2 简」目前 0 人；截止时仍只有一边有人押，全额退款")
        .contains("概率和预计赔率随奖池变化，已扣抽成，最终以截止时为准");
    png(anon(get(path(s.path("imageUrl").asString()))));
    User b = user("500");
    bet(b.id(), m, 1, "52");
    advance(Duration.ofMinutes(2));
    String t2 = share("kind=countdown&no=" + m.no() + "&lang=en", null).path("text").asString();
    assertThat(t2).startsWith("3 min to close").contains("1 bettors · 52.00 USDT").doesNotContain("0 bettors so far");
    advance(Duration.ofMinutes(4));
    assertThat(anon(get("/api/public/cards/countdown/" + m.no() + ".png")).getStatus()).isEqualTo(404);
  }

  @Test
  void 周报卡_数字和周报一致_将来的周和写法不对的周404() throws Exception {
    Market m = shortMarket(2);
    User a = user("500");
    User b = user("500");
    bet(a.id(), m, 0, "40");
    bet(b.id(), m, 1, "60");
    closeAndEnd(m);
    settle(m, 1);
    String week = pub.weeks(1).items().get(0).week();
    MarketPublicService.Week w = pub.week(week);
    JsonNode s = share("kind=weekly&week=" + week + "&lang=zh-CN", null);
    assertThat(s.path("url").asString()).isEqualTo("https://app.forecast.test/results/weekly/" + week + "?src=share&cmp=card-weekly&cl=zh-CN");
    String text = s.path("text").asString();
    assertThat(text).startsWith(week + " 周报").contains("结束场数 · " + w.markets()).contains("有效场数 · " + w.settled())
        .contains("奖池合计（USDT） · " + CardService.money(w.poolTotal())).contains("派彩合计（USDT） · " + CardService.money(w.payoutTotal()))
        .contains("抽成合计（USDT） · " + CardService.money(w.feeTotal()));
    png(anon(get(path(s.path("imageUrl").asString()))));
    assertThat(anon(get("/api/public/cards/weekly/2099-W01.png")).getStatus()).isEqualTo(404);
    assertThat(anon(get("/api/public/cards/weekly/abc.png")).getStatus()).isEqualTo(404);
  }

  @Test
  void F12_每种卡片三种语言的文字都没有禁用词_繁体里没有简体字() throws Exception {
    Market done = shortMarket(2);
    User a = user("500");
    User b = user("500");
    bet(b.id(), done, 0, "10");
    bet(a.id(), done, 1, "10");
    closeAndEnd(done);
    settle(done, 0);
    Market live = market(2);
    bet(a.id(), live, 0, "10");
    Market countdown = market(2, Instant.now(clock).plus(Duration.ofMinutes(4)), null);
    List<String> all = new ArrayList<>();
    for (String l : List.of("zh-CN", "zh-TW", "en")) {
      List<String> texts = new ArrayList<>();
      texts.add(share("kind=market&no=" + live.no() + "&lang=" + l, null).path("text").asString());
      texts.add(share("kind=countdown&no=" + countdown.no() + "&lang=" + l, null).path("text").asString());
      texts.add(share("kind=market&no=" + done.no() + "&lang=" + l, null).path("text").asString());
      texts.add(share("kind=weekly&week=" + pub.weeks(1).items().get(0).week() + "&lang=" + l, null).path("text").asString());
      texts.add(body(create(a.cookie(), Map.of("kind", "challenge", "marketNo", live.no(), "optionNo", live.options().get(0), "lang", l))).path("text").asString());
      texts.add(body(create(b.cookie(), Map.of("kind", "win", "marketNo", done.no(), "showAmount", true, "lang", l))).path("text").asString());
      for (String t : texts) {
        for (String w : BANNED) assertThat(t.toLowerCase(Locale.ROOT)).as(l + "：" + t).doesNotContain(w.toLowerCase(Locale.ROOT));
        if (l.equals("zh-TW")) {
          // 题目是测试数据（"简"），不算；只看模板的字
          String tpl = t.replace("简", "").replace("曼城 vs 利物浦", "");
          for (String simp : List.of("币", "账", "结算", "费", "这", "说", "们", "现", "额", "单", "数据", "时间", "赔率", "奖池")) {
            assertThat(tpl).as("繁体里有简体字 " + simp + "：" + t).doesNotContain(simp);
          }
        }
        if (l.equals("en")) {
          // 管理员写的作废 / 取消原因是原文（SQ-31），不算
          String own = String.join("\n", t.lines().filter(x -> !x.startsWith("Voided / cancelled:") && !x.startsWith("Reason:")).toList());
          assertThat(own.replaceAll("曼城 vs 利物浦|选项\\d|简", "")).as("英文里不能有中文：" + t).doesNotContainPattern("[\\u4e00-\\u9fff]");
        }
      }
      all.addAll(texts);
    }
    assertThat(all).hasSize(18);
  }

  @Test
  void OpenGraph_分享链接的页面头部有卡片图片_预览语言跟着cl_robots放行卡片图片() throws Exception {
    Market m = market(2);
    User a = user("500");
    bet(a.id(), m, 0, "10");
    String head = anon(get("/api/public/seo/head").header("X-Fc-Page", "/markets/" + m.no() + "?src=share&cmp=card-market&ref=abcdefghjk&cl=zh-CN"))
        .getContentAsString();
    assertThat(head).contains("<meta property=\"og:image\" content=\"https://app.forecast.test/api/public/cards/market/" + m.no() + ".png?lang=zh-CN\">")
        .contains("<meta property=\"og:image:width\" content=\"1200\">").contains("<meta property=\"og:image:height\" content=\"630\">")
        .contains("<meta name=\"twitter:card\" content=\"summary_large_image\">").contains("<meta property=\"og:title\" content=\"曼城 vs 利物浦 简")
        .contains("og:locale\" content=\"zh_CN\"");
    // 规范地址仍是不带参数的英文页（x-default），分享参数不影响收录
    assertThat(head).contains("<link rel=\"canonical\" href=\"https://app.forecast.test/markets/" + m.no() + "\">");
    // 挑战卡的链接：图片是这张挑战卡，标题是卡片上那句话
    String url = body(create(a.cookie(), Map.of("kind", "challenge", "marketNo", m.no(), "optionNo", m.options().get(0), "lang", "zh-TW"))).path("url").asString();
    String token = url.substring(url.indexOf("card=") + 5);
    String h2 = anon(get("/api/public/seo/head").header("X-Fc-Page", url.substring(url.indexOf("/m/")).replace("/m/", "/markets/"))).getContentAsString();
    assertThat(h2).contains("/api/public/cards/s/" + token + ".png?lang=zh-TW").contains("twitter:title\" content=\"我押了「选项1 繁」，誰敢押「选项2 繁」？");
    // 别的市场的凭证不用（不能把 A 市场的挑战卡挂到 B 市场的链接上）
    Market other = market(2);
    String h3 = anon(get("/api/public/seo/head").header("X-Fc-Page", "/markets/" + other.no() + "?card=" + token)).getContentAsString();
    assertThat(h3).contains("/api/public/cards/market/" + other.no() + ".png").doesNotContain(token);
    // 周报页
    String week = pub.weeks(1).items().get(0).week();
    assertThat(anon(get("/api/public/seo/head").header("X-Fc-Page", "/results/weekly/" + week)).getContentAsString())
        .contains("/api/public/cards/weekly/" + week + ".png?lang=en");
    // robots：卡片图片放行（X 的爬虫遵守 robots.txt），其余 /api/ 照旧禁止
    String robots = anon(get("/api/public/robots.txt")).getContentAsString();
    assertThat(robots).contains("Allow: /api/public/cards/\n").contains("Disallow: /api/\n");
    assertThat(robots.indexOf("Allow: /api/public/cards/")).isLessThan(robots.indexOf("Disallow: /api/"));
  }

  @Test
  void 很长的题目和很多选项_画得出来_写另有几个选项() throws Exception {
    Market m = market(6);
    jdbc.update("UPDATE market_question SET title_zh_cn = ?, title_en = ? WHERE id = ?", "这是一个非常非常长的题目，用来检查卡片的折行和截断，".repeat(4),
        "This is a very long title used to check wrapping and truncation on the share card ".repeat(3), m.id());
    String text = share("kind=market&no=" + m.no() + "&lang=zh-CN", null).path("text").asString();
    assertThat(text).contains("另有 2 个选项");
    for (String l : List.of("zh-CN", "en")) png(anon(get("/api/public/cards/market/" + m.no() + ".png?lang=" + l)));
  }

  /** 这个客户的分享码（S15，account_share_code） */
  String shareCode(long uid) {
    return jdbc.queryForObject("SELECT code FROM account_share_code WHERE user_id = ?", String.class, uid);
  }
}
