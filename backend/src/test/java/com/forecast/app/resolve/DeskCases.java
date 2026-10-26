package com.forecast.app.resolve;

import com.forecast.app.resolve.api.ResolveDeskApi;
import java.util.Optional;

/**
 * 测试里在裁决台的某个分组找一个市场。测试共用一个库，分组里的条数随用例的执行顺序变（例如体育参数不全的用例会留下很多"自动裁决失败"），
 * 只看第一页（每页最多 100 条）时，排在后面的市场找不到（整合 3 的 CI 发现），所以一页一页找完。
 */
final class DeskCases {

  private DeskCases() {}

  static Optional<ResolveDeskApi.CaseItem> find(ResolveDeskApi desk, String tab, String marketNo) {
    for (int page = 1; ; page++) {
      ResolveDeskApi.Page<ResolveDeskApi.CaseItem> p = desk.cases(tab, page, 100);
      Optional<ResolveDeskApi.CaseItem> hit = p.items().stream().filter(c -> c.marketNo().equals(marketNo)).findFirst();
      if (hit.isPresent() || p.items().isEmpty() || (long) page * p.pageSize() >= p.total()) return hit;
    }
  }
}
