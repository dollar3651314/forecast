package com.forecast.app.content.event;

import com.forecast.app.content.api.ContentAdminApi;
import com.forecast.app.content.check.Words;
import com.forecast.app.content.core.ContentMapper;
import com.forecast.app.content.core.ContentRows;
import com.forecast.app.resolve.api.FixtureFeedApi;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 统一的"事件候选"（forecast S15；自动化需求第 3.1、3.3、3.4 节，AC-101、AC-103）：经济日历、S8 的赛程（只读）、焦点战建议、已过滤日志放在一起。
 * <ul>
 *   <li><b>敏感题过滤</b>（两层的第一层：品类白名单 + 敏感词；第二层 Gemini 分类第一期没有做，开发记录写明）：赛程只有足球、NBA（白名单），队名、联赛名命中
 *       敏感词（青年队、U19、未成年……）的不出现在建议里，记"已过滤"日志（同一场只记一次）。</li>
 *   <li><b>焦点战建议</b>（规则排，不用 AI）：未来 48 小时、还没开赛的比赛，按联赛优先级、开赛时间是不是北京时间晚上（19:00～23:59）、两个来源的对应是否已确认打分；
 *       每天（UTC）工作日 1 场、周末 2 场（MQ-1）；每条写理由；建议玩法是大小球 2.5 / NBA 大小分（MQ-10）。<b>只是建议</b>：生成草稿仍在 S8 的"体育赛程"里由人操作。</li>
 * </ul>
 */
@Service
public class CandidateService {

  private static final Logger log = LoggerFactory.getLogger(CandidateService.class);

  /** 联赛优先级（数字小的优先）；不在表里的 50 */
  static final Map<String, Integer> PRIORITY = new LinkedHashMap<>();

  static {
    PRIORITY.put("CL", 1);
    PRIORITY.put("WC", 1);
    PRIORITY.put("EC", 1);
    PRIORITY.put("PL", 2);
    PRIORITY.put("NBA", 3);
    PRIORITY.put("PD", 4);
    PRIORITY.put("BL1", 5);
    PRIORITY.put("SA", 6);
    PRIORITY.put("FL1", 7);
    PRIORITY.put("DED", 8);
    PRIORITY.put("PPL", 9);
    PRIORITY.put("BSA", 10);
    PRIORITY.put("ELC", 11);
  }

  static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.ROOT);

  private final FixtureFeedApi fixtures;
  private final ContentMapper mapper;
  private final Words words;
  private final EconService econ;
  private final Clock clock;

  CandidateService(FixtureFeedApi fixtures, ContentMapper mapper, Words words, EconService econ, Clock clock) {
    this.fixtures = fixtures;
    this.mapper = mapper;
    this.words = words;
    this.econ = econ;
    this.clock = clock;
  }

  public ContentAdminApi.Candidates candidates() {
    Instant now = clock.instant();
    List<FixtureFeedApi.Item> items;
    String err = null;
    try {
      items = fixtures.upcoming(now, now.plus(Duration.ofHours(48)), 300);
    } catch (RuntimeException e) {
      log.warn("取赛程失败", e);
      items = List.of();
      err = "赛程暂时取不到：" + e.getMessage();
    }
    Map<String, String> filtered = filter(items);
    List<ContentAdminApi.Fixture> rows = new ArrayList<>();
    for (FixtureFeedApi.Item f : items) {
      rows.add(new ContentAdminApi.Fixture(f.fixtureNo(), f.sport(), f.competitionName(), f.home(), f.away(), f.kickoffAt() == null ? null
          : f.kickoffAt().toString(), f.mappingConfirmed(), f.markets(), filtered.containsKey(f.fixtureNo()), filtered.get(f.fixtureNo())));
    }
    List<ContentAdminApi.Filtered> fl = mapper.filters(100).stream().map(CandidateService::filteredView).toList();
    return new ContentAdminApi.Candidates(suggest(items, filtered.keySet(), now), rows, econ.list(), fl, err);
  }

  /** 敏感词过滤：命中的记日志（同一场只记一次），返回 赛程编号 → 命中的词 */
  Map<String, String> filter(List<FixtureFeedApi.Item> items) {
    Map<String, String> out = new LinkedHashMap<>();
    List<String> sensitive = words.list(Words.SENSITIVE);
    LocalDateTime now = LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
    for (FixtureFeedApi.Item f : items) {
      String text = f.home() + " vs " + f.away() + " · " + f.competitionName();
      List<String> hit = Words.find(text, sensitive);
      String reason = null;
      String matched = null;
      if (!"FOOTBALL".equals(f.sport()) && !"NBA".equals(f.sport())) {
        reason = "CATEGORY";
        matched = f.sport();
      } else if (!hit.isEmpty()) {
        reason = "KEYWORD";
        matched = String.join("、", hit);
      }
      if (reason != null) {
        out.put(f.fixtureNo(), matched);
        mapper.insertFilterIgnore("SPORTS", f.fixtureNo(), text.length() > 300 ? text.substring(0, 300) : text, reason, matched, now);
      }
    }
    return out;
  }

  /** 焦点战建议（规则打分） */
  List<ContentAdminApi.Suggestion> suggest(List<FixtureFeedApi.Item> items, Set<String> filtered, Instant now) {
    record Scored(FixtureFeedApi.Item f, int score, List<String> reasons) {}
    Map<LocalDate, List<Scored>> byDay = new LinkedHashMap<>();
    for (FixtureFeedApi.Item f : items) {
      if (filtered.contains(f.fixtureNo()) || f.kickoffAt() == null || !f.kickoffAt().isAfter(now.plus(Duration.ofMinutes(30)))) continue;
      if (f.status() != null && !"scheduled".equals(f.status())) continue;
      int prio = PRIORITY.getOrDefault(f.competition() == null ? "" : f.competition(), 50);
      List<String> reasons = new ArrayList<>();
      int score = 100 - prio * 5;
      reasons.add((f.competitionName() == null ? f.competition() : f.competitionName()) + (prio < 50 ? "（优先联赛第 " + prio + " 档）" : "（不在优先联赛表里）"));
      ZonedDateTime bj = f.kickoffAt().atZone(ZoneOffset.ofHours(8));
      boolean evening = bj.getHour() >= 19;
      if (evening) score += 20;
      reasons.add("开赛 " + TIME.format(f.kickoffAt().atZone(ZoneOffset.UTC)) + " UTC（北京时间 " + TIME.format(bj) + (evening ? "，晚上，华语用户方便看）" : "）"));
      if (f.mappingConfirmed()) {
        score += 10;
        reasons.add("两个数据源的比赛对应已确认，可以自动裁决");
      } else {
        reasons.add("两个数据源的对应还没确认：生成草稿前先在\"体育赛程\"里确认，否则只能人工裁决");
      }
      if (f.markets() > 0) {
        score -= 30;
        reasons.add("已经生成过 " + f.markets() + " 个市场");
      }
      byDay.computeIfAbsent(f.kickoffAt().atZone(ZoneOffset.UTC).toLocalDate(), k -> new ArrayList<>()).add(new Scored(f, score, reasons));
    }
    List<ContentAdminApi.Suggestion> out = new ArrayList<>();
    for (Map.Entry<LocalDate, List<Scored>> e : byDay.entrySet()) {
      DayOfWeek dow = e.getKey().getDayOfWeek();
      int n = dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY ? 2 : 1;
      List<Scored> top = e.getValue().stream().sorted(Comparator.comparingInt(Scored::score).reversed().thenComparing(s -> s.f().kickoffAt())).limit(n).toList();
      Set<String> seen = new HashSet<>();
      for (Scored s : top) {
        if (!seen.add(s.f().fixtureNo())) continue;
        FixtureFeedApi.Item f = s.f();
        out.add(new ContentAdminApi.Suggestion(f.fixtureNo(), f.sport(), f.competition(), f.competitionName(), f.home(), f.away(), f.kickoffAt().toString(),
            f.mappingConfirmed(), f.markets(), "NBA".equals(f.sport()) ? "NBA_TOTAL" : "FOOTBALL_OU 2.5", s.reasons()));
      }
    }
    return out;
  }

  static ContentAdminApi.Filtered filteredView(ContentRows.Filter f) {
    return new ContentAdminApi.Filtered(f.source, f.ref, f.title, f.reason, f.matched, f.createdAt.toInstant(ZoneOffset.UTC).toString());
  }
}
