package com.forecast.app.content;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** 内容中心的日志只增不改（forecast S15，自动化需求第 7 节"日志"）：Mapper 里没有 content_log 的 UPDATE / DELETE。 */
class ContentAppendOnlyTest {

  @Test
  void 日志只增不改() throws Exception {
    String xml;
    try (InputStream in = getClass().getResourceAsStream("/mapper/content/ContentMapper.xml")) {
      xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    assertThat(xml).contains("INSERT INTO content_log");
    assertThat(xml.replaceAll("\\s+", " ")).doesNotContainPattern("(?i)UPDATE content_log").doesNotContainPattern("(?i)DELETE FROM content_log");
  }
}
