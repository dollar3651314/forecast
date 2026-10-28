package com.forecast.app.market.pub;

import com.forecast.app.common.config.ConfigKey;
import com.forecast.app.common.config.SystemConfig;
import com.forecast.app.market.api.MarketCategory;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 不登录也能看的页面文字（forecast S13，MF-4）：{@code resources/content/public-pages.json} 三种语言，加上参数设置里的当前值（"事实"）。
 * 服务器输出给搜索引擎的页面（{@link SeoPages}）和客户端说明页（{@code GET /api/public/pages/how-to-play}）共用这一份（C6：文案不写死在代码里）。
 */
@Component
public class PublicContent {

  /** 第一期三种语言（RQ-18）；取不到用英文 */
  public static final List<String> LANGS = List.of("en", "zh-CN", "zh-TW");

  private final JsonNode root;
  private final SystemConfig config;
  private final String brand;

  PublicContent(ObjectMapper json, SystemConfig config, @Value("${forecast.public.brand-name:Quick Come}") String brand) {
    this.config = config;
    this.brand = brand == null || brand.isBlank() ? "Quick Come" : brand.strip();
    try (InputStream in = PublicContent.class.getResourceAsStream("/content/public-pages.json")) {
      if (in == null) throw new IllegalStateException("缺少 content/public-pages.json");
      this.root = json.readTree(in);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public String brand() {
    return brand;
  }

  /** 把客户端、地址里的语言写法统一成三种之一（zh-TW、zh-HK、zh-MO、zh-Hant 用繁体，其他 zh 用简体，其余英文；和客户端 urlLang 一致） */
  public static String lang(String raw) {
    if (raw == null || raw.isBlank()) return "en";
    String v = raw.strip().replace('_', '-').toLowerCase(Locale.ROOT);
    if (v.equals("zh-tw") || v.equals("zh-hk") || v.equals("zh-mo") || v.startsWith("zh-hant")) return "zh-TW";
    if (v.startsWith("zh")) return "zh-CN";
    return "en";
  }

  /** 一段文字，例如 {@code text("zh-CN", "market.closes")}；{brand} 和参数先替换好；取不到时用英文，英文也没有返回键名 */
  public String text(String lang, String path) {
    return text(lang, path, Map.of());
  }

  public String text(String lang, String path, Map<String, String> vars) {
    String v = raw(lang(lang), path);
    if (v == null) v = raw("en", path);
    if (v == null) return path;
    return fill(v, vars);
  }

  private String raw(String lang, String path) {
    JsonNode n = root.path(lang);
    for (String k : path.split("\\.")) n = n.path(k);
    return n.isString() ? n.asString() : null;
  }

  /** 替换 {brand}、参数设置的当前值和调用方给的变量 */
  String fill(String s, Map<String, String> vars) {
    Map<String, String> all = new LinkedHashMap<>(facts());
    all.put("brand", brand);
    all.putAll(vars);
    String out = s;
    for (Map.Entry<String, String> e : all.entrySet()) out = out.replace("{" + e.getKey() + "}", e.getValue() == null ? "" : e.getValue());
    return out;
  }

  /**
   * 说明页用到的参数设置的当前值（需求 P14 的事实性描述，C6 不写死）：单笔最小提现、默认费率（各品类一样时是一个数，不一样时是范围）、
   * 冷静期、白名单生效时间、大额提现的门槛和延迟、KYC 阈值。
   */
  public Map<String, String> facts() {
    Map<String, String> f = new LinkedHashMap<>();
    f.put("minWithdraw", plain(config.decimal(ConfigKey.WITHDRAW_MIN_AMOUNT, new BigDecimal("100"))));
    TreeSet<BigDecimal> fees = new TreeSet<>();
    for (MarketCategory c : MarketCategory.values()) {
      BigDecimal v = config.decimal(c.feeKey, null);
      if (v != null) fees.add(v.stripTrailingZeros());
    }
    f.put("fee", fees.isEmpty() ? "5%" : fees.size() == 1 ? plain(fees.first()) + "%" : plain(fees.first()) + "%–" + plain(fees.last()) + "%");
    f.put("coolingHours", plain(config.decimal(ConfigKey.WITHDRAW_COOLING_HOURS, new BigDecimal("2"))));
    f.put("whitelistHours", plain(config.decimal(ConfigKey.WHITELIST_EFFECTIVE_HOURS, new BigDecimal("24"))));
    f.put("largeThreshold", plain(config.decimal(ConfigKey.WITHDRAW_LARGE_THRESHOLD, new BigDecimal("3000"))));
    f.put("largeDelayHours", plain(config.decimal(ConfigKey.WITHDRAW_LARGE_DELAY_HOURS, new BigDecimal("6"))));
    f.put("kycThreshold", plain(config.decimal(ConfigKey.KYC_DEPOSIT_THRESHOLD, new BigDecimal("1000"))));
    return f;
  }

  private static String plain(BigDecimal v) {
    return v == null ? "" : v.stripTrailingZeros().toPlainString();
  }

  /** 说明页（P14）：标题、引言、三段（怎么玩、怎么充、怎么提），参数已替换成当前值 */
  public record HowTo(String lang, String title, String description, String h1, String intro, List<Section> sections, Map<String, String> facts) {}

  public record Section(String id, String heading, List<String> items) {}

  private Section section(JsonNode s) {
    List<String> items = new ArrayList<>();
    for (JsonNode i : s.path("items")) items.add(fill(i.asString(), Map.of()));
    return new Section(s.path("id").asString(), fill(s.path("heading").asString(), Map.of()), items);
  }

  public HowTo howTo(String rawLang) {
    String lang = lang(rawLang);
    JsonNode h = root.path(lang).path("howto");
    List<Section> sections = new ArrayList<>();
    for (JsonNode s : h.path("sections")) {
      sections.add(section(s));
      // S18（P2）：只在这种语言显示的段落（例如繁中的台湾入金教程）插在 after 指的那一段后面
      for (JsonNode x : root.path("local").path(lang).path("howto").path("sections")) {
        if (s.path("id").asString().equals(x.path("after").asString())) sections.add(section(x));
      }
    }
    return new HowTo(lang, text(lang, "howto.title"), text(lang, "howto.description"), text(lang, "howto.h1"), text(lang, "howto.intro"), sections, facts());
  }
}
