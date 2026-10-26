package com.forecast.app.market.api;

import java.util.List;

/**
 * 别的模块提供的公开页面（forecast S15：自动化程序的 SEO 页面 {@code /insights/**}，MF-11）。market 的搜索引擎版本（{@code market/pub/SeoPages}，
 * Nginx SSI）和站点地图遇到自己不认识的路径时问这里；这样 market 不依赖 content（content 实现这个接口，和 {@link MarketResolutionView} 一样的方向）。
 * 返回的 HTML 必须已经转义；不能有任何客户的个人数据。
 */
public interface PublicPageProvider {

  /** 这个路径归不归这个提供者（例如 /insights、/insights/xxx） */
  boolean handles(String path);

  /** 页面存不存在（不存在 → Nginx 返回 404） */
  boolean exists(String path);

  /** 标题、描述、是否允许收录 */
  record Meta(String title, String description, boolean index) {}

  /** lang：en / zh-CN / zh-TW */
  Meta meta(String path, String lang);

  /**
   * 正文（{@code <main>} 里面的部分）。
   *
   * @param langQuery 站内链接要带的语言参数（"?lang=zh-CN" 或空串）
   */
  String mainHtml(String path, String lang, String langQuery);

  /** 站点地图的一条：路径、最后修改时间（ISO，可以为空） */
  record Entry(String path, String lastModified) {}

  List<Entry> sitemap();
}
