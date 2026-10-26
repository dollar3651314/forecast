package com.forecast.app.content.page;

import com.forecast.app.common.error.BizException;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 不登录也能看的 SEO 页面（forecast S15，MF-11）：客户端 {@code /insights}、{@code /insights/{slug}} 用。和市场详情一样跟"不登录也能看"的开关
 * （{@code SessionFilter.PUBLIC_BROWSE}）。没有任何客户的个人数据。
 */
@RestController
class InsightsController {

  private final Insights insights;

  InsightsController(Insights insights) {
    this.insights = insights;
  }

  record ListView(List<Insights.Item> items) {}

  @GetMapping("/api/public/insights")
  ListView list(@RequestParam(required = false) String lang) {
    return new ListView(insights.list(lang));
  }

  @GetMapping("/api/public/insights/{slug}")
  Insights.View one(@PathVariable String slug, @RequestParam(required = false) String lang) {
    return insights.view(slug, lang).orElseThrow(() -> new BizException("NOT_FOUND", "页面不存在"));
  }
}
