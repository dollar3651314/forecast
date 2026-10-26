package com.forecast.app.content.draft;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 提示里的"数据"清单（forecast S15）：双语的数值和来源，中文提示取中文、英文提示取英文，免得英文稿里出现中文。 */
class PromptsTest {

  @Test
  void 双语的数值和来源_中文提示取中文_英文提示取英文_别的原样() {
    assertThat(Prompts.side("不能 / No", true)).isEqualTo("No");
    assertThat(Prompts.side("不能 / No", false)).isEqualTo("不能");
    assertThat(Prompts.side("本站奖池 / This site's pool", true)).isEqualTo("This site's pool");
    assertThat(Prompts.side("5% / 3%", true)).as("两边都没有中文：原样").isEqualTo("5% / 3%");
    assertThat(Prompts.side("大 2.5 / 小 2.5", true)).as("右边也有中文：原样").isEqualTo("大 2.5 / 小 2.5");
    assertThat(Prompts.side("1250.00", true)).isEqualTo("1250.00");
  }

  @Test
  void 英文提示的数据清单里没有中文() {
    List<Ref> refs = List.of(new Ref("rules.noCancel", "下注后能不能撤销", "Can a bet be cancelled", "不能 / No", "平台规则·说明页 / Platform rules · how-to page", "2026-10-25T00:00:00Z"));
    String en = Prompts.build("ARTICLE", true, refs, "fees", List.of("risk-free"));
    assertThat(en).contains("- Can a bet be cancelled: No (Platform rules · how-to page, 2026-10-25T00:00:00Z)");
    String zh = Prompts.build("ARTICLE", false, refs, "费率", List.of("稳赚"));
    assertThat(zh).contains("- 下注后能不能撤销：不能（平台规则·说明页，2026-10-25T00:00:00Z）");
  }
}
