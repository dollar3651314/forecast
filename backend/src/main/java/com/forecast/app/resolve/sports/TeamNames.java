package com.forecast.app.resolve.sports;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 队名比较（forecast S8：两个数据源的比赛对应，给运营"候选"用；只是建议，最后由人确认，X-12）。
 * 两家的叫法不同：football-data "Manchester United FC" / "Man United"，TheSportsDB "Manchester United"；"FC Bayern München" / "Bayern Munich"。
 * 做法：去掉重音和标点、小写，去掉 FC、AFC、CF 这类俱乐部前后缀和纯数字，查一个常见别名表，再按词重合度和字母二元组相似度打分（0～1）。
 */
final class TeamNames {

  private TeamNames() {}

  /** 俱乐部名里常见、但不区分球队的词 */
  static final Set<String> NOISE = Set.of("fc", "cf", "afc", "sc", "ac", "cd", "ud", "fk", "ssc", "as", "rc", "rcd", "club", "de", "the", "calcio", "sad",
      "bc", "ca", "cp", "sv", "if", "bk", "ec", "sk", "fbc", "ogc", "tsg", "vfl", "vfb", "rb", "us", "ss", "ks");

  /** 常见的别名（规范化以后）→ 统一的叫法 */
  static final Map<String, String> ALIASES = Map.ofEntries(Map.entry("man united", "manchester united"), Map.entry("man utd", "manchester united"),
      Map.entry("man city", "manchester city"), Map.entry("wolves", "wolverhampton wanderers"), Map.entry("spurs", "tottenham hotspur"),
      Map.entry("tottenham", "tottenham hotspur"), Map.entry("nottm forest", "nottingham forest"), Map.entry("nott m forest", "nottingham forest"),
      Map.entry("psg", "paris saint germain"), Map.entry("paris sg", "paris saint germain"), Map.entry("inter", "internazionale"),
      Map.entry("inter milan", "internazionale"), Map.entry("internazionale milano", "internazionale"), Map.entry("milan", "milan"),
      Map.entry("bayern munich", "bayern munchen"), Map.entry("brighton hove albion", "brighton"), Map.entry("brighton and hove albion", "brighton"),
      Map.entry("newcastle", "newcastle united"), Map.entry("west ham", "west ham united"), Map.entry("leeds", "leeds united"),
      Map.entry("la clippers", "los angeles clippers"), Map.entry("la lakers", "los angeles lakers"));

  /** 规范化：去重音、去标点、小写、去掉俱乐部前后缀和纯数字、查别名 */
  static String normalize(String name) {
    if (name == null) return "";
    String s = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    s = s.toLowerCase(Locale.ROOT).replace("&", " and ").replace("ß", "ss").replaceAll("[^a-z0-9 ]", " ");
    List<String> kept = new ArrayList<>();
    for (String t : s.trim().split("\\s+")) {
      if (t.isEmpty() || NOISE.contains(t) || t.matches("\\d+")) continue;
      kept.add(t);
    }
    String n = String.join(" ", kept);
    if (n.isEmpty()) n = s.trim().replaceAll("\\s+", " ");
    return ALIASES.getOrDefault(n, n);
  }

  /** 两个队名的相似度（0～1） */
  static double similarity(String a, String b) {
    String na = normalize(a);
    String nb = normalize(b);
    if (na.isEmpty() || nb.isEmpty()) return 0;
    if (na.equals(nb)) return 1;
    Set<String> ta = new LinkedHashSet<>(List.of(na.split(" ")));
    Set<String> tb = new LinkedHashSet<>(List.of(nb.split(" ")));
    if (ta.containsAll(tb) || tb.containsAll(ta)) return 0.9;
    Set<String> inter = new HashSet<>(ta);
    inter.retainAll(tb);
    Set<String> union = new HashSet<>(ta);
    union.addAll(tb);
    double jaccard = (double) inter.size() / union.size();
    return Math.max(jaccard, dice(na.replace(" ", ""), nb.replace(" ", "")) * 0.9);
  }

  /** 一个队（全名、简称）和另一个名字的最高相似度 */
  static double best(String other, String... names) {
    double m = 0;
    for (String n : names) if (n != null) m = Math.max(m, similarity(n, other));
    return m;
  }

  /** 去掉 FC 这类前后缀、保留原来大小写和重音的名字（拿去数据源里按名字搜） */
  static String display(String name) {
    if (name == null) return null;
    List<String> kept = new ArrayList<>();
    for (String t : name.trim().split("\\s+")) {
      String low = Normalizer.normalize(t, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
      if (NOISE.contains(low) || low.matches("\\d+")) continue;
      kept.add(t);
    }
    return kept.isEmpty() ? name.trim() : String.join(" ", kept);
  }

  /** 字母二元组的 Dice 相似度 */
  static double dice(String a, String b) {
    if (a.length() < 2 || b.length() < 2) return a.equals(b) ? 1 : 0;
    List<String> x = bigrams(a);
    List<String> y = bigrams(b);
    int match = 0;
    List<String> rest = new ArrayList<>(y);
    for (String g : x) {
      if (rest.remove(g)) match++;
    }
    return 2.0 * match / (x.size() + y.size());
  }

  private static List<String> bigrams(String s) {
    List<String> out = new ArrayList<>();
    for (int i = 0; i < s.length() - 1; i++) out.add(s.substring(i, i + 2));
    return out;
  }
}
