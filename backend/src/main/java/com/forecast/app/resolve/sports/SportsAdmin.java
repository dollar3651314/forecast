package com.forecast.app.resolve.sports;

import com.forecast.app.common.error.BizException;
import com.forecast.app.resolve.api.SportsAdminApi;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * {@link SportsAdminApi} 的实现（forecast S8）：赛程、两个来源的比赛对应、生成草稿、规则文本模板。外部调用（同步、找候选）在事务外，排队最多等
 * {@code forecast.sports.interactive-queue-wait}（默认 10 秒），超过就返回"限频排队"，不让后台请求一直卡住（C81）。
 */
@Service
class SportsAdmin implements SportsAdminApi {

  private final FixtureService fixtures;
  private final SportsDrafts drafts;
  private final SportsTemplates templates;
  private final SportsProperties props;

  SportsAdmin(FixtureService fixtures, SportsDrafts drafts, SportsTemplates templates, SportsProperties props) {
    this.fixtures = fixtures;
    this.drafts = drafts;
    this.templates = templates;
    this.props = props;
  }

  @Override
  public Page<Fixture> fixtures(String sport, String mapping, String from, String to, int page, int pageSize) {
    return fixtures.list(sport, mapping, from, to, page, pageSize);
  }

  @Override
  public Optional<Fixture> fixture(long fixtureNo) {
    return fixtures.detail(fixtureNo);
  }

  @Override
  public List<SyncResult> sync(String sport) {
    return fixtures.sync(sport, props.getInteractiveQueueWait());
  }

  @Override
  public Fixture searchCandidates(long fixtureNo, String ref) {
    String r = ref == null || ref.isBlank() ? null : ref.strip();
    if (r != null && !r.matches("[A-Za-z0-9-]{1,40}")) {
      throw new com.forecast.app.common.error.ValidationException(List.of(new com.forecast.app.common.error.ValidationException.Field("ref", "invalid")));
    }
    SportsRows.Fixture f = fixtures.byNo(fixtureNo);
    return fixtures.view(fixtures.searchCandidates(f, r, props.getInteractiveQueueWait()));
  }

  @Override
  public Fixture confirmMapping(long fixtureNo, String ref, long adminId) {
    return fixtures.view(fixtures.confirmMapping(fixtureNo, ref, adminId));
  }

  @Override
  public Fixture clearMapping(long fixtureNo, long adminId) {
    return fixtures.view(fixtures.clearMapping(fixtureNo, adminId));
  }

  @Override
  public Generated generate(long fixtureNo, List<PlayInput> plays, long adminId) {
    return drafts.generate(fixtureNo, plays, adminId);
  }

  @Override
  public List<Template> templates() {
    return templates.list();
  }

  @Override
  public Template updateTemplate(String kind, TemplateInput in, long adminId) {
    if (kind == null || Play.of(kind) == null) throw new BizException("NOT_FOUND", "没有这个玩法的模板");
    return templates.update(kind, in, adminId);
  }
}
