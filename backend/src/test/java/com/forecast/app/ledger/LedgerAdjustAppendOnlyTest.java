package com.forecast.app.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * 余额调整的单子只增不改（forecast S17）：测试入金单 ledger_test_credit（S17A）。全部 Mapper XML 里没有它们的 UPDATE、DELETE、TRUNCATE。
 * 改错了只能再做一笔反方向的调整（和账本 ledger_entry 同一个规则，LedgerInvariantTest）。
 */
class LedgerAdjustAppendOnlyTest {

  static final List<String> TABLES = List.of("ledger_test_credit");

  @Test
  void 调整单只有INSERT() throws Exception {
    Path root = Path.of("src/main/resources/mapper");
    if (!Files.exists(root)) root = Path.of("backend/src/main/resources/mapper");
    int inserts = 0;
    try (Stream<Path> files = Files.walk(root)) {
      for (Path f : files.filter(p -> p.toString().endsWith(".xml")).toList()) {
        String xml = Files.readString(f).replaceAll("(?s)<!--.*?-->", "");
        for (String t : TABLES) {
          assertThat(Pattern.compile("(?is)\\bUPDATE\\s+`?" + t + "\\b").matcher(xml).find()).as(f + " 里有 " + t + " 的修改").isFalse();
          assertThat(Pattern.compile("(?is)\\b(DELETE\\s+FROM|TRUNCATE(\\s+TABLE)?)\\s+`?" + t + "\\b").matcher(xml).find()).as(f + " 里有 " + t + " 的删除").isFalse();
          if (Pattern.compile("(?is)\\bINSERT\\s+INTO\\s+`?" + t + "\\b").matcher(xml).find()) inserts++;
        }
      }
    }
    assertThat(inserts).as("每张表都扫到了写入语句").isEqualTo(TABLES.size());
  }
}
