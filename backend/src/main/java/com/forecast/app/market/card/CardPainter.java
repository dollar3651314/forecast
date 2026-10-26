package com.forecast.app.market.card;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Component;

/**
 * 把一张卡片（{@link CardModel}）画成 1200 × 630 的 PNG（forecast S14，MF-5）。这是 Open Graph / X 大图卡片的标准尺寸（1.91:1），
 * Telegram、X 的链接预览都按它显示。颜色用客户端的设计 token（方向 A 清爽数据，{@code web/client/src/styles/tokens.css} 浅色）：
 * 主色只用于概率；只有加密"涨 / 跌"和押中用绿红（PQ-1、约定 46）；没有闪动、没有装饰性的收益暗示。
 * <p>布局从上到下：品牌和品类、状态 → 大字一句（可选）→ 题目（最多 2～3 行）→ 选项行（概率条、人数、金额、预计赔率）或周报的数字格 →
 * 关键数字 → 规则说明 → 数据时间。放不下时依次减少：说明只留一条、题目只留一行、去掉说明，最后才少画选项（画"…"）。
 */
@Component
class CardPainter {

  static final int W = 1200;
  static final int H = 630;
  private static final int PAD = 64;
  private static final int CONTENT_W = W - 2 * PAD;

  static final Color BG = new Color(0xFFFFFF);
  static final Color SURFACE = new Color(0xF5F7FA);
  static final Color BORDER = new Color(0xDDE2EA);
  static final Color TEXT = new Color(0x0F172A);
  static final Color MUTED = new Color(0x566173);
  static final Color PRIMARY = new Color(0x1D4ED8);
  static final Color UP = new Color(0x0F7A55);
  static final Color DOWN = new Color(0xB42318);
  static final Color TRACK = new Color(0xE3E8F0);
  static final Color NEUTRAL_BAR = new Color(0x8A94A6);

  private final CardFonts fonts;

  CardPainter(CardFonts fonts) {
    this.fonts = fonts;
  }

  byte[] png(CardModel m) {
    BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = img.createGraphics();
    try {
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
      g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
      g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
      paint(g, m);
    } finally {
      g.dispose();
    }
    ByteArrayOutputStream out = new ByteArrayOutputStream(96 * 1024);
    try {
      ImageIO.write(img, "png", out);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return out.toByteArray();
  }

  // ---------------------------------------------------------------------
  // 布局
  // ---------------------------------------------------------------------

  private void paint(Graphics2D g, CardModel m) {
    g.setColor(BG);
    g.fillRect(0, 0, W, H);
    // 顶部一条主色细线 + 品牌、品类、状态
    g.setColor(PRIMARY);
    g.fillRect(0, 0, W, 8);
    int y = 70;
    g.setColor(PRIMARY);
    g.fill(new RoundRectangle2D.Float(PAD, y - 26, 30, 30, 8, 8));
    text(g, m.brand(), PAD + 42, y, 28, true, TEXT);
    int right = W - PAD;
    if (m.status() != null && !m.status().isBlank()) right = pill(g, m.status(), right, y, statusColor(m)) - 12;
    if (m.chip() != null && !m.chip().isBlank()) pill(g, m.chip(), right, y, MUTED);
    y += 28;

    boolean stats = !m.rows().isEmpty() && m.rows().stream().allMatch(r -> r.ratio() < 0);
    int headlineH = m.headline() == null || m.headline().isBlank() ? 0 : 56;
    List<String> titleLines = wrap(m.title(), headlineH > 0 ? 30 : 36, true, CONTENT_W, headlineH > 0 ? 2 : 3);
    int titleLh = headlineH > 0 ? 40 : 46;
    List<String> notes = new ArrayList<>(m.notes());
    int rowH = stats ? 0 : 90;
    int rowsShown = m.rows().size();
    int bottom = H - 50; // 数据时间那一行的上面
    // 放不下时：说明减到一条 → 题目一行 → 选项少画几个
    while (true) {
      int rowsH = stats ? statsHeight(m.rows().size()) : rowsShown * rowH + (rowsShown < m.rows().size() ? 30 : 0);
      int need = headlineH + titleLines.size() * titleLh + 26 + rowsH + 16 + m.facts().size() * 32 + (notes.isEmpty() ? 0 : 6) + notes.size() * 26;
      if (y + need <= bottom) break;
      if (notes.size() > 1) notes = notes.subList(0, 1);
      else if (titleLines.size() > 1) titleLines = wrap(m.title(), headlineH > 0 ? 30 : 36, true, CONTENT_W, 1);
      else if (!notes.isEmpty()) notes = List.of();
      else if (!stats && rowsShown > 2) rowsShown--;
      else break;
    }

    if (headlineH > 0) {
      y += 46;
      text(g, fit(m.headline(), 40, true, CONTENT_W), PAD, y, 40, true, tone(m.highlight(), TEXT));
      y += 10;
    }
    for (String line : titleLines) {
      y += titleLh;
      text(g, line, PAD, y, headlineH > 0 ? 30 : 36, true, headlineH > 0 ? MUTED : TEXT);
    }
    y += 26;
    if (stats) y = statTiles(g, m.rows(), y);
    else {
      for (int i = 0; i < rowsShown; i++) y = optionRow(g, m.rows().get(i), y);
      if (rowsShown < m.rows().size()) {
        y += 24;
        text(g, "…", PAD, y, 20, false, MUTED); // CardService 已经把选项限制在 4 个并写了"另有 N 个选项"，这里只是最后的兜底
        y += 6;
      }
    }
    y += 16;
    for (String f : m.facts()) {
      y += 32;
      text(g, fit(f, 23, false, CONTENT_W), PAD, y, 23, false, TEXT);
    }
    if (!notes.isEmpty()) y += 6;
    for (String n : notes) {
      y += 26;
      text(g, fit(n, 18, false, CONTENT_W), PAD, y, 18, false, MUTED);
    }
    // 底部：分隔线 + 数据时间
    g.setColor(BORDER);
    g.fillRect(PAD, H - 44, CONTENT_W, 1);
    text(g, fit(m.stamp(), 16, false, CONTENT_W), PAD, H - 18, 16, false, MUTED);
  }

  /** 一个选项：名称（押中 / 我押的打 ✓）、右边概率；下面概率条；再下面人数、金额、预计赔率 */
  private int optionRow(Graphics2D g, CardModel.Row r, int y) {
    int top = y;
    Color c = tone(r.tone(), PRIMARY);
    int pctW = r.pct() == null || r.pct().isBlank() ? 0 : width(r.pct(), 30, true);
    String label = (r.mark() ? "✓ " : "") + r.label();
    text(g, fit(label, 26, true, CONTENT_W - pctW - 24), PAD, top + 28, 26, true, r.mark() ? tone(r.tone(), TEXT) : TEXT);
    if (pctW > 0) text(g, r.pct(), W - PAD - pctW, top + 30, 30, true, c);
    int barY = top + 42;
    g.setColor(TRACK);
    g.fill(new RoundRectangle2D.Float(PAD, barY, CONTENT_W, 10, 10, 10));
    double ratio = Math.max(0, Math.min(1, r.ratio()));
    if (ratio > 0) {
      g.setColor(c);
      g.fill(new RoundRectangle2D.Float(PAD, barY, (float) Math.max(10, CONTENT_W * ratio), 10, 10, 10));
    }
    if (r.detail() != null && !r.detail().isBlank()) text(g, fit(r.detail(), 20, false, CONTENT_W), PAD, top + 78, 20, false, MUTED);
    return top + 90;
  }

  private static int statsHeight(int n) {
    int perLine = 4;
    int lines = (n + perLine - 1) / perLine;
    return lines * 104 + (lines - 1) * 14;
  }

  /** 周报的数字格：一行 4 格，名称在上（小字）、数字在下（大字） */
  private int statTiles(Graphics2D g, List<CardModel.Row> rows, int y) {
    int perLine = 4;
    int gap = 16;
    int w = (CONTENT_W - gap * (perLine - 1)) / perLine;
    int h = 104;
    for (int i = 0; i < rows.size(); i++) {
      int col = i % perLine;
      int line = i / perLine;
      int x = PAD + col * (w + gap);
      int ty = y + line * (h + 14);
      g.setColor(SURFACE);
      g.fill(new RoundRectangle2D.Float(x, ty, w, h, 14, 14));
      CardModel.Row r = rows.get(i);
      text(g, fit(r.label(), 19, false, w - 32), x + 18, ty + 38, 19, false, MUTED);
      text(g, fit(r.detail(), 32, true, w - 36), x + 18, ty + 84, 32, true, r.mark() ? tone(r.tone(), TEXT) : TEXT);
    }
    return y + statsHeight(rows.size());
  }

  /** 右对齐的小标签，返回它的左边位置 */
  private int pill(Graphics2D g, String s, int right, int baseline, Color color) {
    int tw = width(s, 20, true);
    int w = tw + 28;
    int x = right - w;
    g.setColor(SURFACE);
    g.fill(new RoundRectangle2D.Float(x, baseline - 27, w, 36, 18, 18));
    g.setColor(BORDER);
    g.setStroke(new BasicStroke(1f));
    g.draw(new RoundRectangle2D.Float(x, baseline - 27, w, 36, 18, 18));
    text(g, s, x + 14, baseline - 2, 20, true, color);
    return x;
  }

  private static Color statusColor(CardModel m) {
    return switch (m.kind()) {
      case "countdown", "challenge" -> PRIMARY;
      default -> MUTED;
    };
  }

  static Color tone(CardModel.Tone t, Color fallback) {
    if (t == null) return fallback;
    return switch (t) {
      case UP -> UP;
      case DOWN -> DOWN;
      case PRIMARY -> PRIMARY;
      case NEUTRAL -> fallback == PRIMARY ? NEUTRAL_BAR : fallback;
    };
  }

  // ---------------------------------------------------------------------
  // 文字（按字符选字体：拉丁 / 中文，CardFonts.runs）
  // ---------------------------------------------------------------------

  int width(String s, float size, boolean bold) {
    if (s == null) return 0;
    double w = 0;
    for (CardFonts.Run r : fonts.runs(s, size, bold)) w += metrics(r).stringWidth(r.text());
    return (int) Math.ceil(w);
  }

  private static FontMetrics metrics(CardFonts.Run r) {
    BufferedImage scratch = SCRATCH.get();
    Graphics2D g = scratch.createGraphics();
    try {
      g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
      return g.getFontMetrics(r.font());
    } finally {
      g.dispose();
    }
  }

  private static final ThreadLocal<BufferedImage> SCRATCH = ThreadLocal.withInitial(() -> new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB));

  private void text(Graphics2D g, String s, int x, int baseline, float size, boolean bold, Color color) {
    if (s == null || s.isEmpty()) return;
    g.setColor(color);
    float cx = x;
    for (CardFonts.Run r : fonts.runs(s, size, bold)) {
      g.setFont(r.font());
      g.drawString(r.text(), cx, baseline);
      cx += g.getFontMetrics().stringWidth(r.text());
    }
  }

  /** 一行放不下时截断加"…" */
  String fit(String s, float size, boolean bold, int max) {
    if (s == null) return "";
    if (width(s, size, bold) <= max) return s;
    int[] cps = s.codePoints().toArray();
    int lo = 0;
    int hi = cps.length;
    while (lo < hi) {
      int mid = (lo + hi + 1) / 2;
      if (width(new String(cps, 0, mid) + "…", size, bold) <= max) lo = mid;
      else hi = mid - 1;
    }
    return new String(cps, 0, lo).stripTrailing() + "…";
  }

  /**
   * 折行：中文按字、英文按词（空格）折；超过 maxLines 行时最后一行截断加"…"。
   */
  List<String> wrap(String s, float size, boolean bold, int max, int maxLines) {
    List<String> lines = new ArrayList<>();
    if (s == null || s.isBlank()) return lines;
    List<String> tokens = tokens(s.strip());
    StringBuilder cur = new StringBuilder();
    for (int i = 0; i < tokens.size(); i++) {
      String t = tokens.get(i);
      String next = cur + t;
      if (cur.isEmpty() || width(next.stripTrailing(), size, bold) <= max) {
        cur.append(t);
        continue;
      }
      lines.add(cur.toString().stripTrailing());
      cur.setLength(0);
      cur.append(t.stripLeading());
      if (lines.size() == maxLines) {
        // 已经满了：把剩下的都接到最后一行再截断
        StringBuilder rest = new StringBuilder(lines.remove(lines.size() - 1));
        for (int j = i; j < tokens.size(); j++) rest.append(tokens.get(j));
        lines.add(fit(rest.toString(), size, bold, max));
        return lines;
      }
    }
    if (!cur.isEmpty()) lines.add(fit(cur.toString().stripTrailing(), size, bold, max));
    if (lines.size() > maxLines) {
      List<String> keep = new ArrayList<>(lines.subList(0, maxLines - 1));
      keep.add(fit(String.join("", lines.subList(maxLines - 1, lines.size())), size, bold, max));
      return keep;
    }
    return lines;
  }

  /** 中文、日文等每个字一个词；拉丁字母、数字连在一起（带后面的空格）一个词 */
  static List<String> tokens(String s) {
    List<String> out = new ArrayList<>();
    StringBuilder word = new StringBuilder();
    for (int i = 0; i < s.length();) {
      int cp = s.codePointAt(i);
      i += Character.charCount(cp);
      if (Character.isWhitespace(cp)) {
        word.appendCodePoint(cp);
        out.add(word.toString());
        word.setLength(0);
      } else if (Character.isIdeographic(cp) || Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN || isCjkPunct(cp)) {
        if (!word.isEmpty()) {
          out.add(word.toString());
          word.setLength(0);
        }
        out.add(new String(Character.toChars(cp)));
      } else {
        word.appendCodePoint(cp);
      }
    }
    if (!word.isEmpty()) out.add(word.toString());
    return out;
  }

  private static boolean isCjkPunct(int cp) {
    return (cp >= 0x3000 && cp <= 0x303F) || (cp >= 0xFF00 && cp <= 0xFFEF);
  }
}
