package com.forecast.app.growth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.forecast.app.common.web.PrelaunchSite;
import com.forecast.app.growth.waitlist.WaitlistService;
import com.forecast.app.settle.SettleTestSupport;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/**
 * forecast S18 台湾预热（市场/v1-台湾第一期推广.md P1、P2）。真实 MySQL、Redis（C37）。
 * <ul>
 *   <li>P1：从对外预热域名进来（Nginx 设 X-Fc-Site: prelaunch）时，根路径的搜索引擎版本是数据页；canonical、语言互相标注、og:url 都指向预热域名自己，
 *       带分享图；正文不带客户端导航；确认、退订页 noindex；没有这个头时和以前一样（canonical 指向客户端域名）</li>
 *   <li>P1：配了预热域名时，等候页确认邮件里的链接用预热域名</li>
 *   <li>P2：说明页的"怎么充"后面，繁中多一段台湾入金教程；简中、英文没有</li>
 * </ul>
 */
class S18PrelaunchTest extends SettleTestSupport {

  @Autowired WaitlistService waitlist;

  private MockHttpServletResponse www(MockHttpServletRequestBuilder b) throws Exception {
    return call(b.header(PrelaunchSite.HEADER, PrelaunchSite.PRELAUNCH).header("Host", "www.forecast.test").header("X-Forwarded-Proto", "https"), null, null);
  }

  @Test
  void P1_预热域名的根路径是数据页_地址指向预热域名自己_带分享图() throws Exception {
    String h = www(get("/api/public/seo/head").header("X-Fc-Page", "/")).getContentAsString();
    assertThat(h).contains("<title>").contains("<meta name=\"description\"")
        .contains("<link rel=\"canonical\" href=\"https://www.forecast.test/\">")
        .contains("hreflang=\"zh-TW\" href=\"https://www.forecast.test/?lang=zh-TW\"").contains("hreflang=\"x-default\" href=\"https://www.forecast.test/\"")
        .contains("<meta property=\"og:url\" content=\"https://www.forecast.test/\">")
        .contains("<meta property=\"og:image\" content=\"https://www.forecast.test/og-prelaunch.png\">")
        .contains("<meta name=\"twitter:card\" content=\"summary_large_image\">")
        .doesNotContain("noindex").doesNotContain("app.forecast.test");
    String zh = www(get("/api/public/seo/head").header("X-Fc-Page", "/?lang=zh-TW&src=line&cmp=seed")).getContentAsString();
    String dataTitle = call(get("/api/public/seo/head").header("X-Fc-Page", "/data?lang=zh-TW"), null, null).getContentAsString();
    String title = dataTitle.substring(dataTitle.indexOf("<title>"), dataTitle.indexOf("</title>") + 8);
    assertThat(zh).as("和数据页同一个标题").contains(title).contains("<link rel=\"canonical\" href=\"https://www.forecast.test/?lang=zh-TW\">")
        .contains("og:locale\" content=\"zh_TW\"");
    // 正文：数据页的内容，没有客户端的导航（预热域名上没有市场等页面）
    String body = www(get("/api/public/seo/body").header("X-Fc-Page", "/?lang=zh-TW")).getContentAsString();
    assertThat(body).contains("class=\"seo-static\" lang=\"zh-TW\"").contains("<h1>").doesNotContain("<nav>").doesNotContain("href=\"/markets\"");
    // 确认、退订链接的页面不收录，也没有正文
    assertThat(www(get("/api/public/seo/head").header("X-Fc-Page", "/data/confirm?t=x")).getContentAsString()).contains("noindex");
    assertThat(www(get("/api/public/seo/body").header("X-Fc-Page", "/data/confirm?t=x")).getContentAsString()).isEmpty();
    // 没有 Nginx 的头：和以前一样（根路径是市场列表，地址是客户端域名）
    String app = call(get("/api/public/seo/head").header("X-Fc-Page", "/").header("Host", "www.forecast.test"), null, null).getContentAsString();
    assertThat(app).contains("https://app.forecast.test/").doesNotContain("og-prelaunch.png");
    // 别的值不算
    String other = call(get("/api/public/seo/head").header("X-Fc-Page", "/").header(PrelaunchSite.HEADER, "x"), null, null).getContentAsString();
    assertThat(other).doesNotContain("og-prelaunch.png");
  }

  @Test
  void P1_预热域名只认合法的域名写法() {
    assertThat(WaitlistService.prelaunchBase("www.quickcome.online")).contains("https://www.quickcome.online");
    assertThat(WaitlistService.prelaunchBase(" WWW.QuickCome.online ")).contains("https://www.quickcome.online");
    for (String bad : new String[] {"", " ", null, "https://www.quickcome.online", "www.quickcome.online/", "localhost", "a b.com", "-x.com", "x..com"}) {
      assertThat(WaitlistService.prelaunchBase(bad)).as(String.valueOf(bad)).isEmpty();
    }
  }

  @Test
  void P1_配了预热域名时确认邮件的链接用预热域名() throws Exception {
    Object old = ReflectionTestUtils.getField(waitlist, "linkBase");
    ReflectionTestUtils.setField(waitlist, "linkBase", "https://www.forecast.test");
    try {
      String email = "wl-s18-" + System.nanoTime() + "@example.com";
      long n = System.nanoTime();
      MockHttpServletResponse r = call(post("/api/public/waitlist").with(q -> {
        q.setRemoteAddr("198.18." + ((n >> 8) & 0xff) + "." + (n & 0xff));
        return q;
      }), null, "{\"email\":\"" + email + "\",\"lang\":\"zh-TW\",\"website\":\"\",\"attribution\":{\"src\":\"line\",\"cmp\":\"seed\"}}");
      assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
      List<Map<String, Object>> m = jdbc.queryForList("SELECT JSON_UNQUOTE(JSON_EXTRACT(t.payload, '$.params.confirm')) AS confirm, "
          + "JSON_UNQUOTE(JSON_EXTRACT(t.payload, '$.params.unsubscribe')) AS unsub FROM notify_record r JOIN common_task t ON JSON_EXTRACT(t.payload, '$.recordId') = r.id "
          + "WHERE r.recipient = ? AND r.template_code = 'WAITLIST_CONFIRM'", email);
      assertThat(m).hasSize(1);
      assertThat(String.valueOf(m.get(0).get("confirm"))).matches("https://www\\.forecast\\.test/data/confirm\\?t=[0-9a-f]{32}");
      assertThat(String.valueOf(m.get(0).get("unsub"))).matches("https://www\\.forecast\\.test/data/unsubscribe\\?t=[0-9a-f]{32}");
      assertThat(jdbc.queryForMap("SELECT src, cmp FROM growth_waitlist WHERE email = ?", email)).containsEntry("src", "line").containsEntry("cmp", "seed");
    } finally {
      ReflectionTestUtils.setField(waitlist, "linkBase", old);
    }
  }

  @Test
  void P2_台湾入金教程只在繁中_插在怎么充后面() throws Exception {
    JsonNode tw = json.readTree(call(get("/api/public/pages/how-to-play?lang=zh-TW"), null, null).getContentAsString());
    List<String> ids = new java.util.ArrayList<>();
    tw.path("sections").forEach(s -> ids.add(s.path("id").asString()));
    assertThat(ids).containsExactly("play", "deposit", "deposit-tw", "withdraw");
    JsonNode sec = tw.path("sections").get(2);
    assertThat(sec.path("heading").asString()).contains("MAX").contains("BitoPro");
    String all = sec.path("items").toString();
    assertThat(all).contains("TRC20").contains("ERC20").contains("BEP20").contains("Quick Come").doesNotContain("{brand}").doesNotContain("運彩");
    assertThat(sec.has("draft")).as("待定稿标记不给页面").isFalse();
    for (String l : new String[] {"zh-CN", "en"}) {
      JsonNode o = json.readTree(call(get("/api/public/pages/how-to-play?lang=" + l), null, null).getContentAsString());
      List<String> x = new java.util.ArrayList<>();
      o.path("sections").forEach(s -> x.add(s.path("id").asString()));
      assertThat(x).as(l).containsExactly("play", "deposit", "withdraw");
    }
    // 搜索引擎版本的说明页同样带这一段
    assertThat(call(get("/api/public/seo/body").header("X-Fc-Page", "/how-to-play?lang=zh-TW"), null, null).getContentAsString()).contains("id=\"deposit-tw\"");
  }
}
