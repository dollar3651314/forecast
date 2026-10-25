package com.forecast.app.account.attribution;

import com.forecast.app.account.api.AttributionApi;
import com.forecast.app.common.attribution.Attribution;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 客户的来源（forecast S13，MF-1）：注册时把浏览器里记的"首次来源"和"最近一次来源（注册来源）"写进 {@code account_attribution}（同一个注册事务）；
 * 后台来源统计按它分组。值的清洗、Telegram start 参数的解码在 {@link Attribution}。<b>不涉及任何钱</b>（MQ-5）。
 */
@Service
public class AttributionService implements AttributionApi {

  /** 浏览器传来的一次来源（值是原文，这里清洗） */
  public record Touch(String src, String cmp, String ref, String start, String at) {}

  private final AttributionMapper mapper;
  private final Clock clock;

  AttributionService(AttributionMapper mapper, Clock clock) {
    this.mapper = mapper;
    this.clock = clock;
  }

  /**
   * 注册事务里调用：first、last 都清洗后为空时不写。只带了一个时另一个按它（第一次带参数访问后就注册：首次来源 = 注册来源）。
   *
   * @return 是否写了一行
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean record(long userId, Touch first, Touch last) {
    Instant now = clock.instant();
    Attribution.Source f = source(first);
    Attribution.Source l = source(last);
    if (f.isEmpty() && l.isEmpty()) return false;
    Touch ft = f.isEmpty() ? last : first;
    Touch lt = l.isEmpty() ? first : last;
    if (f.isEmpty()) f = l;
    if (l.isEmpty()) l = f;
    AttributionMapper.Row r = new AttributionMapper.Row();
    r.userId = userId;
    r.firstSrc = f.src();
    r.firstCmp = f.cmp();
    r.firstRef = f.ref();
    r.firstStart = f.start();
    r.firstAt = ldt(Attribution.at(ft == null ? null : ft.at(), now));
    r.regSrc = l.src();
    r.regCmp = l.cmp();
    r.regRef = l.ref();
    r.regStart = l.start();
    r.regSeenAt = ldt(Attribution.at(lt == null ? null : lt.at(), now));
    r.createdAt = ldt(now);
    return mapper.insert(r) == 1;
  }

  private static Attribution.Source source(Touch t) {
    return t == null ? new Attribution.Source(null, null, null, null) : Attribution.of(t.src(), t.cmp(), t.ref(), t.start());
  }

  private static LocalDateTime ldt(Instant i) {
    return LocalDateTime.ofInstant(i.truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
  }

  /** 一个客户的来源（测试和后台核对用；客户端没有接口能读到它） */
  @Transactional(readOnly = true)
  public Registration find(long userId) {
    AttributionMapper.Row r = mapper.find(userId);
    return r == null ? null : view(r, null);
  }

  @Override
  @Transactional(readOnly = true)
  public List<Registration> registrations(Instant from, Instant to, int limit) {
    return mapper.registrations(ldt(from), ldt(to), limit).stream().map(r -> view(r, r.registeredAt)).toList();
  }

  private static Registration view(AttributionMapper.Row r, LocalDateTime registeredAt) {
    Source first = r.firstSrc == null && r.firstCmp == null && r.firstRef == null && r.firstStart == null ? null
        : new Source(r.firstSrc, r.firstCmp, r.firstRef, r.firstStart);
    Source reg = r.regSrc == null && r.regCmp == null && r.regRef == null && r.regStart == null ? null : new Source(r.regSrc, r.regCmp, r.regRef, r.regStart);
    return new Registration(r.userId, registeredAt == null ? null : registeredAt.toInstant(ZoneOffset.UTC), first, reg);
  }
}
