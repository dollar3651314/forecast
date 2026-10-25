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

  private static final Pattern VAR = Pattern.compile("\\{([A-Za-z0-9_]+)}");

  /** 变量名 → 值 → {中文, 英文} */
  private static final Map<String, Map<String, String[]>> WORDS = Map.of(
      "status", Map.of(
          "APPROVED", new String[] {"审核已通过，即将上链", "has been approved and will be sent on-chain shortly"},
          "DELAYED", new String[] {"审核已通过，大额提现将在延迟期满后上链", "has been approved; as a large withdrawal it will be sent after the delay period"},
          "REJECTED", new String[] {"已被驳回，资金已退回", "was rejected and the funds have been returned"},
          "BROADCASTING", new String[] {"已发送到链上，等待确认", "has been sent on-chain and is awaiting confirmation"},
          "COMPLETED", new String[] {"已完成", "has been completed"},
          "FAILED_REFUNDED", new String[] {"上链失败，资金已全部退回", "failed on-chain and the funds have been fully refunded"},
          "CANCELLED", new String[] {"已被取消，资金已退回", "was cancelled and the funds have been returned"}),
      "side", Map.of(
          "BUY", new String[] {"买入", "Buy"},
          "SELL", new String[] {"卖出", "Sell"}),
      "trigger", Map.of(
          "TP", new String[] {"止盈", "take profit"},
          "SL", new String[] {"止损", "stop loss"}),
      "result", Map.of(
          "APPROVED", new String[] {"已通过，现在可以提现", "has been approved. You can now withdraw"},
          "REJECTED", new String[] {"未通过", "was rejected"}),
      // forecast S5：市场取消 / 作废退款（#13）。refundReason 是系统作废的原因代码；取消、人工作废时是运营填的原因，原样显示
      "refundKind", Map.of(
          "CANCELLED", new String[] {"已取消", "was cancelled"},
          "VOIDED", new String[] {"已作废", "was voided"}),
      "refundReason", Map.of(
          "NOT_ENOUGH_OPTIONS", new String[] {"截止时有人押的选项不足 2 个，按规则作废", "fewer than two options had bets at the close, so the market was voided under the rules"},
          "NO_WINNER", new String[] {"没有人押结果对应的选项，按规则作废", "nobody bet on the winning option, so the market was voided under the rules"},
          "NO_POOL", new String[] {"退回晚于开赛的下注后奖池为 0，按规则作废", "after refunding bets placed after the start the pool was empty, so the market was voided"},
          "CANCELLED", new String[] {"市场已取消", "the market was cancelled"},
          "VOIDED", new String[] {"市场已作废", "the market was voided"}));

  record Text(String title, String body) {}

  private Render() {}

  /** 客户端语言 → 支持的语言（默认中文） */
  static String lang(String lang) {
    return EN.equalsIgnoreCase(lang) ? EN : ZH;
  }

  static Text render(Rows.TemplateRow t, Map<String, String> params, String lang) {
    boolean en = EN.equals(lang(lang));
    return new Text(fill(en ? t.titleEn : t.titleZh, params, en), fill(en ? t.bodyEn : t.bodyZh, params, en));
  }

  /** 不知道收件人语言时（按邮箱发的邮件）：中英文各一份；中英文标题前各加一个前缀（测试环境标识，{@link EnvLabel}，没有时传空串） */
  static Text bilingual(Rows.TemplateRow t, Map<String, String> params, String zhPrefix, String enPrefix) {
    Text zh = render(t, params, ZH);
    Text en = render(t, params, EN);
    return new Text(zhPrefix + zh.title() + " | " + enPrefix + en.title(), zh.body() + "\n\n----------\n\n" + en.body());
  }

  static String fill(String text, Map<String, String> params, boolean en) {
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
        m.appendReplacement(sb, Matcher.quoteReplacement(word(m.group(1), v, en)));
      }
      if (missing) continue;
      m.appendTail(sb);
      out.add(sb.toString());
    }
    return String.join("\n", out);
  }

  private static String word(String var, String value, boolean en) {
    Map<String, String[]> dict = WORDS.get(var);
    String[] w = dict == null ? null : dict.get(value);
    return w == null ? value : w[en ? 1 : 0];
  }
}
