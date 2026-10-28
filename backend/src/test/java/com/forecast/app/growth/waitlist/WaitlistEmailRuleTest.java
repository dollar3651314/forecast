package com.forecast.app.growth.waitlist;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * forecast S19（C108）：导入时的邮箱规则和客户端、Cloudflare 的等候页是同一条，三边跑同一组例子 resources/email/vectors.json。
 */
class WaitlistEmailRuleTest {

  @Test
  void 和客户端_Cloudflare_同一组例子() throws Exception {
    try (InputStream in = getClass().getResourceAsStream("/email/vectors.json")) {
      JsonNode cases = new ObjectMapper().readTree(in).get("cases");
      assertThat(cases.size()).isGreaterThan(10);
      for (JsonNode c : cases) {
        assertThat(WaitlistService.validEmail(c.get("input").asString())).as(c.get("input").asString()).isEqualTo(c.get("ok").asBoolean());
      }
    }
  }
}
