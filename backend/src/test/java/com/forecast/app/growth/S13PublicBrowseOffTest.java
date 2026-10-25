package com.forecast.app.growth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 关掉"不登录也能看"（forecast S13，{@code forecast.public-browse.enabled=false}，部署时 {@code FC_PUBLIC_BROWSE_ENABLED=false}）：
 * 市场列表、今日场次、详情、开奖记录、周报、说明页、奖池推送的握手都和以前一样要登录（401）；robots.txt 禁止收录；给 Nginx 的页面头部照常有（品牌标题）。
 */
@SpringBootTest(properties = "forecast.public-browse.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class S13PublicBrowseOffTest {

  @Autowired MockMvc mvc;

  int status(String url) throws Exception {
    return mvc.perform(get(url)).andReturn().getResponse().getStatus();
  }

  @Test
  void 关掉以后公开接口都要登录_robots禁止收录() throws Exception {
    for (String url : new String[] {"/api/markets", "/api/markets/today", "/api/markets/123", "/api/public/results", "/api/public/weekly",
        "/api/public/weekly/2026-W44", "/api/public/pages/how-to-play", "/api/ws/markets"}) {
      assertThat(status(url)).as(url).isEqualTo(401);
    }
    String robots = mvc.perform(get("/api/public/robots.txt")).andReturn().getResponse().getContentAsString();
    assertThat(robots).contains("Disallow: /\n").doesNotContain("Sitemap");
    assertThat(mvc.perform(get("/api/public/sitemap.xml")).andReturn().getResponse().getContentAsString()).doesNotContain("<loc>");
    assertThat(status("/api/public/seo/head")).isEqualTo(200);
  }
}
