package com.forecast.app.market.api;

import java.util.List;
import java.util.Optional;

/**
 * 别的模块提供的公开页面（forecast S16：growth 模块的数据页 {@code /data}，MF-8、MF-17），给搜索引擎版本（{@code market/pub/SeoPages}，Nginx SSI）
 * 和站点地图用。market 只定义接口，<b>由提供页面的模块实现</b>（和 {@link MarketPoolView} 同样的依赖方向）；没有实现时这些地址按"登录后的页面"处理（不收录）。
 * 返回的 HTML 片段由实现方负责转义。
 */
public interface PublicPageView {

  /**
   * 一个页面的标题、描述，是否允许收录。
   *
   * @param title 已经带品牌名
   */
  record Meta(String title, String description, boolean index) {}

  /** 这个路径是不是本实现提供的页面（只看路径，不含查询参数） */
  boolean handles(String path);

  /**
   * 标题和描述；lang：en / zh-CN / zh-TW。
   *
   * @param brand 品牌名
   */
  Meta meta(String path, String lang, String brand);

  /** 正文（放在 {@code <main>} 里）；q 是链接要带的语言参数（"?lang=zh-CN" 或空串）；不输出时为空 */
  Optional<String> main(String path, String lang, String q);

  /** 要列进站点地图的路径（每个路径三种语言 + x-default） */
  List<String> sitemapPaths();
}
