package com.forecast.app.content.draft;

import java.util.List;

/**
 * 发给 Gemini 的提示（forecast S15）：写作规则（自动化需求第 4.3 节，红线）+ 这一类内容的要求 + "引用的数据"清单 + 输出格式（JSON：title、body）。
 * 中文稿和英文稿各自起草（MQ-8：不是互相翻译）。提示里只有公开数据和汇总数据（NF-16），发之前还要过 {@code PiiGuard}。
 * 规则写成"只能用清单里的数字"，生成后程序再核对一遍（{@code NumberCheck}），对不上就标红、不能发布。
 */
final class Prompts {

  private Prompts() {}

  /** 每一类的要求：中文、英文 */
  static String spec(String kind, boolean en) {
    return switch (kind) {
      case "PREVIEW" -> en
          ? "Write a short pre-match data note for our featured match (80-150 words, title up to 12 words). Describe how the pool is split, the number of bettors and the times. Do not predict the result."
          : "写一篇焦点战的赛前数据短文（120～220 字，标题不超过 30 字）：介绍两边奖池的分布、人数和时间。不预测比赛结果。";
      case "BRIEF" -> en
          ? "Write a short BTC market note (60-120 words, title up to 12 words) using the hourly statistics and, if given, the two sides of our BTC market. Do not predict the price."
          : "写一篇 BTC 行情短文（80～150 字，标题不超过 30 字）：用每小时涨跌统计，有市场数据时也写本站这一场两边的比例。不预测价格。";
      case "SEO_INTRO" -> en
          ? "Write the introduction of a public data page (2-3 sentences, 40-80 words; title up to 12 words): what the data on this page is and how to read it. No conclusions."
          : "写公开数据页开头的说明（2～3 句，60～120 字；标题不超过 30 字）：说明这一页的数据是什么、怎么看。不下结论。";
      case "ARTICLE" -> en
          ? "Write a website article (400-700 words, title up to 14 words) on the topic below. Use paragraphs separated by a blank line; subheadings start with \"## \". Explain the rules plainly."
          : "就下面的题目写一篇网站文章（600～1000 字，标题不超过 30 字）。段落之间空一行，小标题以\"## \"开头。用平实的话讲清楚规则。";
      case "POST_X" -> en
          ? "Write one post for our official X account (at most 240 characters, no hashtags spam, no link: the link is added later). Title: a 3-6 word label for the reviewer."
          : "写一条官方 X 账号的帖子（不超过 120 字，不要堆砌话题标签，不写链接：链接由系统附上）。标题：给审核人看的 3～10 字的简短说明。";
      case "POST_TG" -> en
          ? "Write one post for our official Telegram channel (at most 400 characters, no link: the link is added later). Title: a 3-6 word label for the reviewer."
          : "写一条官方 Telegram 频道的帖子（不超过 200 字，不写链接：链接由系统附上）。标题：给审核人看的 3～10 字的简短说明。";
      case "REPLY" -> en
          ? "Draft a polite reply (at most 240 characters) to the viewpoint below, putting it next to the real numbers of our pool. Do not attack anyone. Title: a 3-6 word label for the reviewer."
          : "就下面的观点起草一条礼貌的回复（不超过 120 字）：把它和本站奖池的真实数字放在一起看。不攻击任何人。标题：给审核人看的 3～10 字的简短说明。";
      default -> throw new IllegalArgumentException(kind);
    };
  }

  /** 正文的长度上限（字符数；X 的帖子按平台限制，超过不能发布） */
  static int maxLength(String kind, boolean en) {
    return switch (kind) {
      case "POST_X", "REPLY" -> en ? 280 : 140;
      case "POST_TG" -> en ? 600 : 300;
      case "SEO_INTRO" -> en ? 700 : 300;
      case "PREVIEW", "BRIEF" -> en ? 1400 : 500;
      default -> en ? 8000 : 3000;
    };
  }

  static String build(String kind, boolean en, List<Ref> refs, String topic, List<String> banned) {
    StringBuilder sb = new StringBuilder();
    if (en) {
      sb.append("You write for the official account of a prediction-market website (pari-mutuel pools in USDT). Write in English.\n\n");
      sb.append("Rules (must follow):\n");
      sb.append("1. Use only the numbers in the DATA list below, exactly as given or rounded. Do not compute new numbers (no differences, ratios, averages). Write all numbers as digits.\n");
      sb.append("2. Only present data. Do not predict outcomes, do not say which side is more likely, do not suggest what to bet on.\n");
      sb.append("3. Never use promotional or misleading claims, including: ").append(String.join("; ", banned)).append(".\n");
      sb.append("4. Write as the official account; do not pretend to be a third party or a trader.\n");
      sb.append("5. If you mention Polymarket, you must say the data comes from Polymarket's public API and is not affiliated with Polymarket.\n");
      sb.append("6. If the pool is small or nobody has bet, say so honestly.\n");
      sb.append("7. Do not use numbered lists. Do not include links.\n\n");
      sb.append("Task: ").append(spec(kind, true)).append("\n");
      if (topic != null && !topic.isBlank()) sb.append("Topic: ").append(topic).append("\n");
      sb.append("\nDATA (source, time):\n");
      for (Ref r : refs) sb.append("- ").append(r.labelEn()).append(": ").append(side(r.value(), true)).append(" (").append(side(r.source(), true)).append(", ").append(r.at()).append(")\n");
      sb.append("\nReturn only a JSON object: {\"title\": \"...\", \"body\": \"...\"}.\n");
    } else {
      sb.append("你替一个预测市场网站（USDT 彩池玩法）的官方账号写内容。用简体中文写。\n\n");
      sb.append("规则（必须遵守）：\n");
      sb.append("1. 只用下面\"数据\"清单里的数字，原样写或按四舍五入写；不要自己算新的数字（差值、倍数、平均数都不要）；数字一律用阿拉伯数字。\n");
      sb.append("2. 只摆数据：不预测结果，不说哪边更可能赢，不建议押哪边。\n");
      sb.append("3. 不能用这些说法：").append(String.join("、", banned)).append("。\n");
      sb.append("4. 以官方账号的身份写，不冒充第三方，不写\"作为一个资深操盘手\"之类。\n");
      sb.append("5. 提到 Polymarket 时必须写\"数据来自 Polymarket 公开接口，非 Polymarket 官方\"。\n");
      sb.append("6. 奖池小、人少就照实写，不凑整、不放大。\n");
      sb.append("7. 不要用编号列表，不要写链接。\n\n");
      sb.append("任务：").append(spec(kind, false)).append("\n");
      if (topic != null && !topic.isBlank()) sb.append("题目：").append(topic).append("\n");
      sb.append("\n数据（来源，时间）：\n");
      for (Ref r : refs) sb.append("- ").append(r.label()).append("：").append(side(r.value(), false)).append("（").append(side(r.source(), false)).append("，").append(r.at()).append("）\n");
      sb.append("\n只返回一个 JSON 对象：{\"title\": \"……\", \"body\": \"……\"}。\n");
    }
    return sb.toString();
  }

  private static final java.util.regex.Pattern CJK = java.util.regex.Pattern.compile("[\\u3000-\\u9fff\\uff00-\\uffef]");

  /**
   * 双语的数值或来源（"不能 / No"、"本站奖池 / This site's pool"）：中文提示取左边，英文提示取右边，免得英文稿里出现中文（S15 端到端截图发现）。
   * 只在左边有中文、右边没有中文时拆；别的原样（题目里本来就有 " / " 的不拆）。
   */
  static String side(String text, boolean en) {
    if (text == null) return "";
    int i = text.lastIndexOf(" / ");
    if (i <= 0) return text;
    String left = text.substring(0, i);
    String right = text.substring(i + 3);
    if (!CJK.matcher(left).find() || CJK.matcher(right).find()) return text;
    return en ? right : left;
  }
}
