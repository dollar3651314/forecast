package com.forecast.app.content.check;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 发给 Gemini 之前的检查（forecast S15，NF-16、EX-5、AC-116）：提示里只应该有公开数据和汇总数据；发现像邮箱、链上地址、私钥 / 交易哈希、API key、
 * IP 地址、"password=" 一类的字符串就拦下（整次调用不发），调用方记日志（只记命中的种类，不记原文）。
 * 客户编号是纯数字、认不出来：靠"提示只从公开快照拼"保证（{@code DataRefs} 不取任何客户字段，测试核对）。
 */
public final class PiiGuard {

  private PiiGuard() {}

  static final Map<String, Pattern> PATTERNS = new LinkedHashMap<>();

  static {
    PATTERNS.put("email", Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"));
    PATTERNS.put("evmAddress", Pattern.compile("0x[0-9a-fA-F]{40}"));
    PATTERNS.put("tronAddress", Pattern.compile("(?<![A-Za-z0-9])T[1-9A-HJ-NP-Za-km-z]{33}(?![A-Za-z0-9])"));
    PATTERNS.put("btcAddress", Pattern.compile("(?<![A-Za-z0-9])(bc1[0-9a-z]{20,80}|[13][1-9A-HJ-NP-Za-km-z]{25,34})(?![A-Za-z0-9])"));
    PATTERNS.put("longHex", Pattern.compile("(?<![0-9a-fA-Fx])(0x)?[0-9a-fA-F]{64}(?![0-9a-fA-F])"));
    PATTERNS.put("apiKey", Pattern.compile("AIza[0-9A-Za-z_\\-]{30,}|ci_only_[A-Za-z0-9_]+|sk-[A-Za-z0-9]{20,}"));
    PATTERNS.put("secret", Pattern.compile("(?i)(api[_-]?key|secret|password|passwd|private[_-]?key|token)\\s*[:=]"));
    PATTERNS.put("ip", Pattern.compile("(?<![0-9.])(?:(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)(?![0-9.])"));
  }

  /** 命中的种类（空 = 可以发） */
  public static List<String> scan(String text) {
    if (text == null || text.isEmpty()) return List.of();
    return PATTERNS.entrySet().stream().filter(e -> e.getValue().matcher(text).find()).map(Map.Entry::getKey).toList();
  }
}
