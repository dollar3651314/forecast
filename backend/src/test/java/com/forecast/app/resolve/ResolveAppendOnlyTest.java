package com.forecast.app.resolve;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * 数据源原始返回存档、裁决记录只增不改、永久保留（技术方案第 3.3、6.3 节；需求 NF-08、NF-09；forecast S6）：全部 Mapper XML 里这几张表没有 UPDATE、DELETE、TRUNCATE。
 * forecast S7：加裁决的存档截图 resolve_attachment；裁决记录在 S6 的 ResolveMapper 和 S7 的 PlatformMapper 里都有写入（提议、确认、退回、争议结论都是新的一行）。
 */
class ResolveAppendOnlyTest {

  static final List<String> TABLES = List.of("resolve_source_fetch", "resolve_record", "resolve_attachment");

  @Test
  void 存档和裁决记录没有修改和删除语句() throws Exception {
    Path root = Path.of("src/main/resources/mapper");
    if (!Files.exists(root)) root = Path.of("backend/src/main/resources/mapper");
    int inserts = 0;
    try (Stream<Path> files = Files.walk(root)) {
      for (Path f : files.filter(p -> p.toString().endsWith(".xml")).toList()) {
        String xml = Files.readString(f).replaceAll("(?s)<!--.*?-->", "");
        for (String t : TABLES) {
          assertThat(Pattern.compile("(?is)\\b(UPDATE|DELETE\\s+FROM|DELETE\\s+\\w+\\s+FROM|TRUNCATE(\\s+TABLE)?)\\s+" + t + "\\b").matcher(xml).find())
              .as(f + " 里有 " + t + " 的修改或删除").isFalse();
          if (Pattern.compile("(?is)\\bINSERT\\s+INTO\\s+" + t + "\\b").matcher(xml).find()) inserts++;
        }
      }
    }
    assertThat(inserts).as("确实扫到了写入：存档 1、裁决记录 2（ResolveMapper、PlatformMapper）、截图 1").isEqualTo(4);
  }
}
