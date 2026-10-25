package com.forecast.app.settle;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * 结算明细、退款明细的金额写入后不改（技术方案/v1-架构方案.md 第 3.1 节"settle_item 的金额列只增不改"；forecast S5）：
 * 全部 Mapper XML 里，这两张表只有改状态和完成时间的 UPDATE，没有改金额的、没有 DELETE / TRUNCATE；结算单、明细、对账差异都不删。
 */
class SettleAppendOnlyTest {

  static final List<String> NO_DELETE = List.of("settle_run", "settle_item", "settle_refund_item", "settle_pool_recon_issue");
  static final List<String> ITEM_TABLES = List.of("settle_item", "settle_refund_item");
  static final List<String> AMOUNT_COLUMNS = List.of("stake", "gross", "fee", "payout", "amount", "user_id", "position_id", "bet_id", "win");

  @Test
  void 明细的金额没有修改语句_结算相关的表没有删除语句() throws Exception {
    Path root = Path.of("src/main/resources/mapper");
    if (!Files.exists(root)) root = Path.of("backend/src/main/resources/mapper");
    int updates = 0;
    try (Stream<Path> files = Files.walk(root)) {
      for (Path f : files.filter(p -> p.toString().endsWith(".xml")).toList()) {
        String xml = Files.readString(f).replaceAll("(?s)<!--.*?-->", "");
        for (String t : NO_DELETE) {
          assertThat(Pattern.compile("(?is)\\b(DELETE\\s+FROM|DELETE\\s+\\w+\\s+FROM|TRUNCATE(\\s+TABLE)?)\\s+" + t + "\\b").matcher(xml).find())
              .as(f + " 里有 " + t + " 的删除").isFalse();
        }
        for (String t : ITEM_TABLES) {
          Matcher m = Pattern.compile("(?is)\\bUPDATE\\s+" + t + "\\b(.*?)\\bWHERE\\b").matcher(xml);
          while (m.find()) {
            updates++;
            String set = m.group(1);
            for (String c : AMOUNT_COLUMNS) {
              assertThat(Pattern.compile("(?i)\\b" + c + "\\s*=").matcher(set).find()).as(f + " 里改了 " + t + "." + c + "：" + set.strip()).isFalse();
            }
          }
        }
      }
    }
    assertThat(updates).as("确实扫到了改状态的语句").isGreaterThanOrEqualTo(2);
  }
}
