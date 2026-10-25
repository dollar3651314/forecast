package com.forecast.app.market.card;

import com.forecast.app.common.health.AlertCheck;
import java.awt.Font;
import java.awt.FontFormatException;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 分享卡片用的字体（forecast S14，MF-5）。卡片图片在服务器上用 Java2D 画（{@link CardPainter}），三种语言都要能显示：
 * <ul>
 *   <li>拉丁字母和数字用 DejaVu Sans（常规、粗体；镜像 {@code eclipse-temurin:25-jre-noble} 自带 {@code fonts-dejavu-core}）；</li>
 *   <li>中文（简体、繁体）用文泉驿正黑（{@code fonts-wqy-zenhei}，{@code deploy/app.Dockerfile} 安装；约 16 MB，简繁都有）；
 *       也认 Noto Sans CJK、Droid Sans Fallback（本机开发时可能装的是它们）。</li>
 * </ul>
 * 按字符选字体：拉丁字体能显示的用拉丁字体，否则用中文字体（{@link #runs}）。一个都找不到时退回 Java 的逻辑字体 SansSerif（中文可能显示成方框），
 * 并在健康检查的 {@code alerts} 里报 {@code cards.font}（不影响 {@code status}，约定 11；开关 {@code forecast.cards.font-alert}，后端测试关掉）。
 * 字体路径可以用 {@code forecast.cards.font-dirs} 改（逗号分隔的目录，按顺序找）。
 */
@Component
class CardFonts implements AlertCheck {

  private static final Logger log = LoggerFactory.getLogger(CardFonts.class);
  /** 中文字体的候选文件名（按顺序） */
  static final List<String> CJK_FILES = List.of("wqy-zenhei.ttc", "NotoSansCJK-Regular.ttc", "NotoSansCJKsc-Regular.otf", "NotoSansSC-Regular.otf",
      "NotoSansTC-Regular.otf", "DroidSansFallbackFull.ttf", "DroidSansFallback.ttf", "wqy-microhei.ttc");
  static final String LATIN_REGULAR = "DejaVuSans.ttf";
  static final String LATIN_BOLD = "DejaVuSans-Bold.ttf";

  private final Font latin;
  private final Font latinBold;
  private final Font cjk;
  private final String cjkFile;
  private final boolean alert;

  CardFonts(@Value("${forecast.cards.font-dirs:/usr/share/fonts,/usr/local/share/fonts}") String dirs,
      @Value("${forecast.cards.font-alert:true}") boolean alert) {
    List<File> roots = new ArrayList<>();
    for (String d : dirs.split(",")) if (!d.isBlank()) roots.add(new File(d.strip()));
    File reg = find(roots, List.of(LATIN_REGULAR));
    File bold = find(roots, List.of(LATIN_BOLD));
    File han = find(roots, CJK_FILES);
    this.latin = load(reg, new Font(Font.SANS_SERIF, Font.PLAIN, 1));
    this.latinBold = bold != null ? load(bold, latin.deriveFont(Font.BOLD)) : latin.deriveFont(Font.BOLD);
    this.cjk = load(han, null);
    this.cjkFile = han == null ? null : han.getPath();
    this.alert = alert;
    if (cjk == null) log.warn("分享卡片：没有找到中文字体（{}，目录 {}），中文卡片可能显示成方框；镜像里要装 fonts-wqy-zenhei", CJK_FILES, roots);
    else log.info("分享卡片字体：拉丁 {}、中文 {}", reg == null ? "SansSerif" : reg.getName(), han.getName());
  }

  /** 在几个目录里（含子目录，最多 4 层）找第一个存在的文件名 */
  static File find(List<File> roots, List<String> names) {
    for (String n : names) {
      for (File r : roots) {
        File f = search(r, n, 0);
        if (f != null) return f;
      }
    }
    return null;
  }

  private static File search(File dir, String name, int depth) {
    if (dir == null || !dir.isDirectory() || depth > 4) return null;
    File direct = new File(dir, name);
    if (direct.isFile()) return direct;
    File[] subs = dir.listFiles(File::isDirectory);
    if (subs == null) return null;
    java.util.Arrays.sort(subs);
    for (File s : subs) {
      File f = search(s, name, depth + 1);
      if (f != null) return f;
    }
    return null;
  }

  private static Font load(File f, Font fallback) {
    if (f == null) return fallback;
    try {
      Font[] fs = Font.createFonts(f); // .ttc 里有好几个字体，取第一个（文泉驿正黑 / Noto Sans CJK 的第一个都是常规体）
      return fs.length > 0 ? fs[0] : fallback;
    } catch (IOException | FontFormatException | RuntimeException e) {
      log.warn("分享卡片：字体 {} 读不了：{}", f, e.toString());
      return fallback;
    }
  }

  boolean hasCjk() {
    return cjk != null;
  }

  /** 一段文字按字体切开的一段 */
  record Run(String text, Font font) {}

  /**
   * 把文字按"拉丁字体能不能显示"切成几段，每段配好字号和粗细。中文字体只有常规体，粗体由 Java2D 加粗（{@code deriveFont(BOLD)}）。
   */
  List<Run> runs(String text, float size, boolean bold) {
    Font l = (bold ? latinBold : latin).deriveFont(bold ? Font.BOLD : Font.PLAIN, size);
    Font c = cjk == null ? l : cjk.deriveFont(bold ? Font.BOLD : Font.PLAIN, size);
    List<Run> out = new ArrayList<>();
    if (text == null || text.isEmpty()) return out;
    StringBuilder cur = new StringBuilder();
    Font curFont = null;
    for (int i = 0; i < text.length();) {
      int cp = text.codePointAt(i);
      Font f = l.canDisplay(cp) || cjk == null || !c.canDisplay(cp) ? l : c;
      if (curFont != null && f != curFont) {
        out.add(new Run(cur.toString(), curFont));
        cur.setLength(0);
      }
      curFont = f;
      cur.appendCodePoint(cp);
      i += Character.charCount(cp);
    }
    if (!cur.isEmpty()) out.add(new Run(cur.toString(), curFont));
    return out;
  }

  @Override
  public List<Item> evaluate() {
    if (!alert) return List.of();
    return List.of(new Item("cards.font", "分享卡片缺中文字体", "Share cards: CJK font missing", cjk != null,
        cjk != null ? "中文字体 " + cjkFile : "没有找到中文字体（" + String.join("、", CJK_FILES) + "），中文、繁体卡片会显示成方框；镜像里装 fonts-wqy-zenhei"));
  }
}
