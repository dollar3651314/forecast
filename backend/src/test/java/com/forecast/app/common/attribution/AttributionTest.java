package com.forecast.app.common.attribution;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** 来源参数的清洗和 Telegram start 参数的解码（forecast S13，MF-1）。 */
class AttributionTest {

  @Test
  void 清洗_不合法的当成没有() {
    assertThat(Attribution.clean(" tg ")).isEqualTo("tg");
    assertThat(Attribution.clean("a.b_c~d-1")).isEqualTo("a.b_c~d-1");
    assertThat(Attribution.clean("<x>")).isNull();
    assertThat(Attribution.clean("a b")).isNull();
    assertThat(Attribution.clean("x".repeat(65))).isNull();
    assertThat(Attribution.clean("")).isNull();
    assertThat(Attribution.of(null, null, null, null).isEmpty()).isTrue();
  }

  @Test
  void start参数_同样的编码_链接上的优先() {
    assertThat(Attribution.of(null, null, null, "s-tg__c-w1__r-10023")).isEqualTo(new Attribution.Source("tg", "w1", "10023", "s-tg__c-w1__r-10023"));
    assertThat(Attribution.of("x", null, null, "s-tg__c-w1")).isEqualTo(new Attribution.Source("x", "w1", null, "s-tg__c-w1"));
    // 解不出来的只留原文
    assertThat(Attribution.of(null, null, null, "hello")).isEqualTo(new Attribution.Source(null, null, null, "hello"));
    assertThat(Attribution.of(null, null, null, "bad start!")).isEqualTo(new Attribution.Source(null, null, null, null));
    assertThat(Attribution.of(null, null, null, "g-x__s-group_42")).isEqualTo(new Attribution.Source("group_42", null, null, "g-x__s-group_42"));
  }

  @Test
  void 浏览器记的时间_在未来或太早的用现在() {
    Instant now = Instant.parse("2026-10-25T00:00:00Z");
    assertThat(Attribution.at("2026-10-20T00:00:00Z", now)).isEqualTo(Instant.parse("2026-10-20T00:00:00Z"));
    assertThat(Attribution.at("2026-10-26T00:00:00Z", now)).isEqualTo(now);
    assertThat(Attribution.at(now.minus(Duration.ofDays(40)).toString(), now)).isEqualTo(now);
    assertThat(Attribution.at("x", now)).isEqualTo(now);
    assertThat(Attribution.at(null, now)).isEqualTo(now);
  }
}
