package com.forecast.app.content.core;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 内容中心的日志（forecast S15，表 {@code content_log}，只增不改）：每次生成、发布、驳回、复制、编辑、过滤、拦截、告警都写一行（自动化需求第 7 节"日志"、AC-118）。
 * 写日志失败不影响业务（只记应用日志）。<b>不写 key、不写发给 Gemini 的原文</b>（只写种类、编号、结果和简短说明）。
 */
@Component
public class ContentLog {

  private static final Logger log = LoggerFactory.getLogger(ContentLog.class);

  private final ContentMapper mapper;
  private final Clock clock;

  ContentLog(ContentMapper mapper, Clock clock) {
    this.mapper = mapper;
    this.clock = clock;
  }

  public void write(String action, String kind, String ref, String lang, String outcome, String detail, int aiCalls, Long adminId) {
    ContentRows.Log l = new ContentRows.Log();
    l.action = action;
    l.kind = kind;
    l.ref = ref == null ? null : cut(ref, 80);
    l.lang = lang;
    l.outcome = outcome;
    l.detail = detail == null ? "" : cut(detail, 1000);
    l.aiCalls = aiCalls;
    l.adminId = adminId;
    l.createdAt = LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
    try {
      mapper.insertLog(l);
    } catch (RuntimeException e) {
      log.warn("内容中心日志没写进去：{} {} {}", action, ref, outcome, e);
    }
  }

  static String cut(String s, int n) {
    return s.length() <= n ? s : s.substring(0, n - 1) + "…";
  }
}
