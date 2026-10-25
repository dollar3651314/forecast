package com.forecast.app.market.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.forecast.app.market.api.I18nText;
import com.forecast.app.market.api.MarketAdminApi;
import com.forecast.app.market.api.MarketStatus;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 客户端市场接口的最小契约（forecast S3；页面在原型确认后 S11 做）：{@code GET /api/markets}、{@code GET /api/markets/{publicNo}} 的字段名、格式，
 * 草稿看不到，要登录。字段一变这里就失败，客户端照着这里接。奖池、概率、预计赔率在 S4 加进来时同步改这里。
 */
class MarketClientContractTest extends MarketTestSupport {

  @Autowired MockMvc mvc;
  final ObjectMapper json = new ObjectMapper();

  Cookie user() throws Exception {
    String email = "mc" + System.nanoTime() + "@example.com";
    jdbc.update("INSERT INTO account_user (email, status) VALUES (?, 'ACTIVE')", email);
    long id = jdbc.queryForObject("SELECT id FROM account_user WHERE email = ?", Long.class, email);
    String token = UUID.randomUUID().toString();
    String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
    jdbc.update("INSERT INTO account_session (user_id, token_hash, created_at, last_seen_at, expires_at) VALUES (?, ?, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3), "
        + "UTC_TIMESTAMP(3) + INTERVAL 1 DAY)", id, hash);
    return new Cookie("fc_session", token);
  }

  MockHttpServletResponse call(String url, Cookie c) throws Exception {
    return mvc.perform(c == null ? get(url) : get(url).cookie(c)).andReturn().getResponse();
  }

  JsonNode body(String url, Cookie c) throws Exception {
    MockHttpServletResponse r = call(url, c);
    assertThat(r.getStatus()).as(url + " " + r.getContentAsString()).isEqualTo(200);
    return json.readTree(r.getContentAsString());
  }

  static List<String> keys(JsonNode n) {
    List<String> out = new ArrayList<>();
    n.propertyNames().forEach(out::add);
    return out;
  }

  /** 建一个上架的足球市场（经后台接口同一套业务代码），返回对外编号 */
  String listedMarket() {
    MarketAdminApi.Detail d = markets.createDraft(complete(), 1);
    markets.publish(Long.parseLong(d.publicNo()), true, 1);
    return d.publicNo();
  }

  @Test
  void 没登录401() throws Exception {
    assertThat(call("/api/markets", null).getStatus()).isEqualTo(401);
    assertThat(call("/api/markets/1", null).getStatus()).isEqualTo(401);
  }

  @Test
  void 列表_字段和格式_草稿不出现() throws Exception {
    Cookie c = user();
    String no = listedMarket();
    MarketAdminApi.Detail draft = markets.createDraft(complete(), 1);
    JsonNode page = body("/api/markets?category=FOOTBALL&status=active&sort=newest&pageSize=50", c);
    assertThat(keys(page)).containsExactly("items", "total", "page", "pageSize");
    JsonNode item = null;
    for (JsonNode i : page.get("items")) {
      assertThat(i.get("publicNo").asString()).isNotEqualTo(draft.publicNo());
      assertThat(i.get("status").asString()).isIn("listed", "paused");
      if (i.get("publicNo").asString().equals(no)) item = i;
    }
    assertThat(item).as("新上架的在第一页（newest）").isNotNull();
    // pool（S4）由 pool 模块提供，这里的替身不给（null）；结构见 pool/PoolClientContractTest
    assertThat(keys(item)).containsExactly("publicNo", "category", "title", "status", "openAt", "closeAt", "eventStartAt", "eventEndAt", "feeRate", "options", "pool");
    assertThat(item.get("publicNo").isString()).as("编号是字符串").isTrue();
    assertThat(item.get("category").asString()).isEqualTo("FOOTBALL");
    assertThat(keys(item.get("title"))).containsExactly("en", "zhCN", "zhTW");
    assertThat(item.get("title").get("zhTW").asString()).isEqualTo("曼城 vs 利物浦 繁");
    assertThat(item.get("closeAt").asString()).isEqualTo(NOW.plus(Duration.ofDays(1)).toString());
    assertThat(item.get("feeRate").asString()).isEqualTo("5");
    assertThat(item.get("options").size()).isEqualTo(3);
    assertThat(keys(item.get("options").get(0))).containsExactly("optionNo", "seq", "label");
    assertThat(item.get("options").get(0).get("optionNo").isString()).isTrue();
    assertThat(item.get("options").get(2).get("label").get("en").asString()).isEqualTo("客胜 en");
  }

  @Test
  void 详情_规则区要的全部字段_补充说明和费率修改记录按时间_草稿和不存在的404() throws Exception {
    Cookie c = user();
    String no = listedMarket();
    long pn = Long.parseLong(no);
    markets.clarify(pn, new I18nText("Stoppage time counts", "含伤停补时", "含傷停補時"), 1);
    advance(Duration.ofMinutes(10));
    markets.changeFee(pn, "8", 1, "调整");
    JsonNode d = body("/api/markets/" + no, c);
    assertThat(keys(d)).containsExactly("publicNo", "category", "title", "rules", "status", "resolutionMode", "sourcePrimary", "sourceBackup", "openAt", "closeAt",
        "eventStartAt", "eventEndAt", "feeRate", "userCap", "disputeWindowMinutes", "coverPath", "cancelReason", "voidReason", "options", "clarifications", "feeChanges",
        "pool");
    assertThat(d.get("status").asString()).isEqualTo("listed");
    assertThat(d.get("feeRate").asString()).isEqualTo("8");
    assertThat(d.get("userCap").asString()).isEqualTo("10000");
    assertThat(d.get("rules").get("zhCN").asString()).isEqualTo("以 90 分钟比分为准 简");
    assertThat(d.get("sourcePrimary").asString()).isEqualTo("premierleague.com");
    assertThat(keys(d.get("options").get(0))).containsExactly("optionNo", "seq", "label", "winCondition");
    assertThat(d.get("options").get(1).get("winCondition").get("zhCN").asString()).isEqualTo("打平 简");
    assertThat(keys(d.get("clarifications").get(0))).containsExactly("text", "time");
    assertThat(d.get("clarifications").get(0).get("text").get("zhTW").asString()).isEqualTo("含傷停補時");
    assertThat(d.get("clarifications").get(0).get("time").asString()).isEqualTo(NOW.toString());
    assertThat(keys(d.get("feeChanges").get(0))).as("公开：时间和旧 → 新，不含原因和操作人").containsExactly("oldRate", "newRate", "time");
    assertThat(d.get("feeChanges").get(0).get("oldRate").asString() + ">" + d.get("feeChanges").get(0).get("newRate").asString()).isEqualTo("5>8");
    assertThat(d.get("feeChanges").get(0).get("time").asString()).isEqualTo(NOW.plus(Duration.ofMinutes(10)).toString());
    MarketAdminApi.Detail draft = markets.createDraft(complete(), 1);
    assertThat(call("/api/markets/" + draft.publicNo(), c).getStatus()).isEqualTo(404);
    assertThat(call("/api/markets/123", c).getStatus()).isEqualTo(404);
    assertThat(call("/api/markets/abc", c).getStatus()).isEqualTo(404);
  }

  @Test
  void 作废的市场_详情带原因代码_列表按分组筛选() throws Exception {
    Cookie c = user();
    long id = insert(MarketStatus.LISTED, NOW.plus(Duration.ofMinutes(1)));
    pool.activeOptions.put(id, 1);
    advance(Duration.ofMinutes(1));
    markets.closeDue();
    JsonNode d = body("/api/markets/" + publicNo(id), c);
    assertThat(d.get("status").asString()).isEqualTo("voided");
    assertThat(d.get("voidReason").asString()).isEqualTo("NOT_ENOUGH_OPTIONS");
    boolean found = false;
    for (JsonNode i : body("/api/markets?status=ended&pageSize=50&sort=newest", c).get("items")) {
      assertThat(i.get("status").asString()).isIn("settled", "cancelled", "voided");
      found |= i.get("publicNo").asString().equals(String.valueOf(publicNo(id)));
    }
    assertThat(found).isTrue();
    for (JsonNode i : body("/api/markets?status=voided&pageSize=5", c).get("items")) assertThat(i.get("status").asString()).isEqualTo("voided");
  }

  @Test
  void 参数不对_一次列出() throws Exception {
    Cookie c = user();
    MockHttpServletResponse r = call("/api/markets?status=draft&category=CHESS&sort=hot", c);
    assertThat(r.getStatus()).isEqualTo(400);
    assertThat(r.getContentAsString()).contains("\"field\":\"status\"").contains("\"field\":\"category\"").contains("\"field\":\"sort\"");
  }
}
