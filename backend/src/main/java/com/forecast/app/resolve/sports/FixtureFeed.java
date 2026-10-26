package com.forecast.app.resolve.sports;

import com.forecast.app.resolve.api.FixtureFeedApi;
import com.forecast.app.resolve.api.SportsAdminApi;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** 赛程的只读视图（forecast S15，{@link FixtureFeedApi}）：就是后台赛程列表（{@link SportsAdminApi#fixtures}）摘出来的字段。 */
@Component
class FixtureFeed implements FixtureFeedApi {

  private static final int PAGE = 100;

  private final SportsAdminApi sports;

  FixtureFeed(SportsAdminApi sports) {
    this.sports = sports;
  }

  @Override
  public List<Item> upcoming(Instant from, Instant to, int max) {
    List<Item> out = new ArrayList<>();
    for (int page = 1; out.size() < max; page++) {
      SportsAdminApi.Page<SportsAdminApi.Fixture> p = sports.fixtures(null, null, from.toString(), to.toString(), page, PAGE);
      for (SportsAdminApi.Fixture f : p.items()) {
        if (out.size() >= max) break;
        out.add(new Item(f.fixtureNo(), f.sport(), f.competition(), f.competitionName(), f.home() == null ? "" : f.home().name(),
            f.away() == null ? "" : f.away().name(), f.kickoffAt() == null ? null : Instant.parse(f.kickoffAt()), f.status(), f.mapping() != null,
            f.markets() == null ? 0 : f.markets().size()));
      }
      if ((long) page * PAGE >= p.total() || p.items().isEmpty()) break;
    }
    return out;
  }
}
