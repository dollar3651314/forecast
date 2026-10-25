package com.forecast.app.resolve.auto;

import com.forecast.app.resolve.source.SourceArchive;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 原始返回存档写进 {@code resolve_source_fetch}（只增不改，永久保留 NF-09）。网关不在事务里调用，每行自己提交。
 */
@Component
class DbSourceArchive implements SourceArchive {

  private final ResolveMapper mapper;
  private final ObjectMapper json;

  DbSourceArchive(ResolveMapper mapper, ObjectMapper json) {
    this.mapper = mapper;
    this.json = json;
  }

  @Override
  public long save(Entry e) {
    ResolveRows.Fetch f = new ResolveRows.Fetch();
    f.marketId = e.marketId();
    f.source = e.source();
    f.attempt = e.attempt();
    f.requestUrl = e.requestUrl().length() > 1000 ? e.requestUrl().substring(0, 1000) : e.requestUrl();
    f.httpStatus = e.httpStatus();
    f.body = e.body();
    f.bodySha256 = e.bodySha256();
    f.outcome = e.outcome();
    f.parsed = e.parsed() == null ? null : json.writeValueAsString(e.parsed());
    f.error = e.error();
    f.fetchedAt = LocalDateTime.ofInstant(e.fetchedAt().truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
    f.elapsedMs = e.elapsedMs();
    mapper.insertFetch(f);
    return f.id;
  }
}
