package com.forecast.app.market.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * 只增不改的表（技术方案第 3.1 节）：状态历史、补充说明、费率修改记录在所有 Mapper XML 里只有 INSERT，没有 UPDATE / DELETE / TRUNCATE。
 * 这些记录是对客户公开的（规则区）和审计用的，改了就失去意义。
 */
class MarketAppendOnlyTest {

  static final List<String> TABLES = List.of("market_status_log", "market_clarification", "market_fee_change", "market_share");

  @Test
  void 只增不改的表没有修改和删除语句() throws Exception {
    Path root = Path.of("src/main/resources/mapper");
    if (!Files.exists(root)) root = Path.of("backend/src/main/resources/mapper");
    try (Stream<Path> files = Files.walk(root)) {
      for (Path f : files.filter(p -> p.toString().endsWith(".xml")).toList()) {
        String xml = Files.readString(f).replaceAll("(?s)<!--.*?-->", "");
        for (String t : TABLES) {
          assertThat(Pattern.compile("(?is)\\b(UPDATE|DELETE\\s+FROM|DELETE\\s+\\w+\\s+FROM|TRUNCATE(\\s+TABLE)?)\\s+" + t + "\\b").matcher(xml).find())
              .as(f + " 里有 " + t + " 的修改或删除").isFalse();
        }
      }
    }
  }
}
