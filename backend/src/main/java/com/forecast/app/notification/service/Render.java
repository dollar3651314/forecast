package com.forecast.app.notification.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 按模板生成标题和正文。
 * <ul>
 *   <li>{@code {变量}} 换成变量的值；枚举值（提现状态、买卖方向、止盈止损、KYC 结果；forecast S5 起退款种类和系统作废的原因代码）按语言翻译。</li>
 *   <li>某一行里有变量没有值时，整行不显示（例如没有驳回原因时不显示"原因"那一行）。</li>
 * </ul>
 */
final class Render {

  static final String ZH = "zh-CN";
  static final String EN = "en";
  /** 繁体中文（forecast S9，RQ-18：客户端三种语言） */
  static final String TW = "zh-TW";

  private static final Pattern VAR = Pattern.compile("\\{([A-Za-z0-9_]+)}");

  /** 变量名 → 值 → {简体, 英文, 繁体} */
  private static final Map<String, Map<String, String[]>> WORDS = Map.of(
      "status", Map.of(
          "APPROVED", new String[] {"审核已通过，即将上链", "has been approved and will be sent on-chain shortly", "審核已通過，即將上鏈"},
          "DELAYED", new String[] {"审核已通过，大额提现将在延迟期满后上链", "has been approved; as a large withdrawal it will be sent after the delay period", "審核已通過，大額提現將在延遲期滿後上鏈"},
          "REJECTED", new String[] {"已被驳回，资金已退回", "was rejected and the funds have been returned", "已被駁回，資金已退回"},
          "BROADCASTING", new String[] {"已发送到链上，等待确认", "has been sent on-chain and is awaiting confirmation", "已發送到鏈上，等待確認"},
          "COMPLETED", new String[] {"已完成", "has been completed", "已完成"},
          "FAILED_REFUNDED", new String[] {"上链失败，资金已全部退回", "failed on-chain and the funds have been fully refunded", "上鏈失敗，資金已全部退回"},
          "CANCELLED", new String[] {"已被取消，资金已退回", "was cancelled and the funds have been returned", "已被取消，資金已退回"}),
      "side", Map.of(
          "BUY", new String[] {"买入", "Buy", "買入"},
          "SELL", new String[] {"卖出", "Sell", "賣出"}),
      "trigger", Map.of(
          "TP", new String[] {"止盈", "take profit", "停利"},
          "SL", new String[] {"止损", "stop loss", "停損"}),
      "result", Map.of(
          "APPROVED", new String[] {"已通过，现在可以提现", "has been approved. You can now withdraw", "已通過，現在可以提現"},
          "REJECTED", new String[] {"未通过", "was rejected", "未通過"}),
      // forecast S5：市场取消 / 作废退款（#13）。refundReason 是系统作废的原因代码；取消、人工作废时是运营填的原因，原样显示
      "refundKind", Map.of(
          "CANCELLED", new String[] {"已取消", "was cancelled", "已取消"},
          "VOIDED", new String[] {"已作废", "was voided", "已作廢"}),
      "refundReason", Map.of(
          "NOT_ENOUGH_OPTIONS", new String[] {"截止时有人押的选项不足 2 个，按规则作废", "fewer than two options had bets at the close, so the market was voided under the rules", "截止時有人押的選項不足 2 個，按規則作廢"},
          "NO_WINNER", new String[] {"没有人押结果对应的选项，按规则作废", "nobody bet on the winning option, so the market was voided under the rules", "沒有人押結果對應的選項，按規則作廢"},
          "NO_POOL", new String[] {"退回晚于开赛的下注后奖池为 0，按规则作废", "after refunding bets placed after the start the pool was empty, so the market was voided", "退回晚於開賽的下注後獎池為 0，按規則作廢"},
          "CANCELLED", new String[] {"市场已取消", "the market was cancelled", "市場已取消"},
          "VOIDED", new String[] {"市场已作废", "the market was voided", "市場已作廢"},
          // forecast S7：E6 延期、E7 腰斩等满 48 小时还没完赛 / 宣布，系统作废
          "POSTPONED_48H", new String[] {"比赛延期，原定开赛后 48 小时内没有完赛，按规则作废", "the event was postponed and not completed within 48 hours of the original start, so the market was voided under the rules", "比賽延期，原定開賽後 48 小時內沒有完賽，按規則作廢"},
          "ABANDONED_48H", new String[] {"比赛中途终止，48 小时内官方没有宣布有效比分，按规则作废", "the event was abandoned and no official result was announced within 48 hours, so the market was voided under the rules", "比賽中途終止，48 小時內官方沒有宣布有效比分，按規則作廢"}),
      // forecast S7：争议结论（#15）
      "decision", Map.of(
          "UPHELD", new String[] {"维持原结果", "the original result stands", "維持原結果"},
          "OVERTURNED", new String[] {"改判", "the result has been changed", "改判"},
          "VOIDED", new String[] {"无法判定，市场作废、下注全额退回", "the result cannot be determined; the market is voided and all bets are refunded", "無法判定，市場作廢、下注全額退回"}),
      // forecast S7：结果是"作废"（补偿 #17 里正确结果是作废、裁决提议是作废）
      "option", Map.of("VOID", new String[] {"作废（全额退款）", "void (full refund)", "作廢（全額退款）"}),
      "optionEn", Map.of("VOID", new String[] {"作废（全额退款）", "void (full refund)", "作廢（全額退款）"}),
      // forecast S7：裁决超时提醒（员工邮件）
      "what", Map.of(
          "PROPOSE", new String[] {"提议", "proposal", "提議"},
          "CONFIRM", new String[] {"确认", "confirmation", "確認"},
          "DISPUTE", new String[] {"争议处理", "dispute handling", "爭議處理"}));

  record Text(String title, String body) {}

  private Render() {}

  /** 客户端语言 → 支持的语言（默认简体）：en；zh-TW / zh-HK / zh-MO / zh-Hant 用繁体（和客户端 urlLang.ts 同一规则）；其他用简体 */
  static String lang(String lang) {
    if (lang == null) return ZH;
    String l = lang.strip();
    if (EN.equalsIgnoreCase(l) || l.toLowerCase(java.util.Locale.ROOT).startsWith("en-")) return EN;
    String lower = l.toLowerCase(java.util.Locale.ROOT);
    if (lower.equals("zh-tw") || lower.equals("zh-hk") || lower.equals("zh-mo") || lower.startsWith("zh-hant")) return TW;
    return ZH;
  }

  /**
   * 按语言生成。繁体：模板的繁体文字为空时用简体（员工模板只有中英文）；变量先取 {@code 名字Tw}（例如 marketTw、optionTw），
   * 没有时用简体的值，所以繁体模板和简体模板写同样的变量名。
   */
  static Text render(Rows.TemplateRow t, Map<String, String> params, String lang) {
    String l = lang(lang);
    if (EN.equals(l)) return new Text(fill(t.titleEn, params, 1), fill(t.bodyEn, params, 1));
    if (TW.equals(l)) {
      boolean hasTw = t.titleTw != null && !t.titleTw.isBlank() && t.bodyTw != null && !t.bodyTw.isBlank();
      if (hasTw) return new Text(fill(t.titleTw, twParams(params), 2), fill(t.bodyTw, twParams(params), 2));
    }
    return new Text(fill(t.titleZh, params, 0), fill(t.bodyZh, params, 0));
  }

  /** 繁体用的变量：有 名字Tw 的用它 */
  static Map<String, String> twParams(Map<String, String> params) {
    if (params == null) return Map.of();
    Map<String, String> out = new java.util.HashMap<>(params);
    for (Map.Entry<String, String> e : params.entrySet()) {
      if (e.getKey().endsWith("Tw") && e.getValue() != null && !e.getValue().isBlank()) out.put(e.getKey().substring(0, e.getKey().length() - 2), e.getValue());
    }
    return out;
  }

  /** 不知道收件人语言时（按邮箱发的邮件）：中英文各一份；中英文标题前各加一个前缀（测试环境标识，{@link EnvLabel}，没有时传空串） */
  static Text bilingual(Rows.TemplateRow t, Map<String, String> params, String zhPrefix, String enPrefix) {
    Text zh = render(t, params, ZH);
    Text en = render(t, params, EN);
    return new Text(zhPrefix + zh.title() + " | " + enPrefix + en.title(), zh.body() + "\n\n----------\n\n" + en.body());
  }

  /** @param idx 0 简体、1 英文、2 繁体（枚举值翻译用） */
  static String fill(String text, Map<String, String> params, int idx) {
    List<String> out = new ArrayList<>();
    for (String line : text.split("\n", -1)) {
      Matcher m = VAR.matcher(line);
      StringBuilder sb = new StringBuilder();
      boolean missing = false;
      while (m.find()) {
        String v = params == null ? null : params.get(m.group(1));
        if (v == null || v.isBlank()) {
          missing = true;
          break;
        }
        m.appendReplacement(sb, Matcher.quoteReplacement(word(m.group(1), v, idx)));
      }
      if (missing) continue;
      m.appendTail(sb);
      out.add(sb.toString());
    }
    return String.join("\n", out);
  }

  private static String word(String var, String value, int idx) {
    Map<String, String[]> dict = WORDS.get(var);
    String[] w = dict == null ? null : dict.get(value);
    return w == null ? value : w[idx];
  }
}
