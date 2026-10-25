package com.forecast.app.growth.visit;

import com.forecast.app.common.attribution.Attribution;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 带来源参数的访问（forecast S13，MF-1）：客户端打开带 src / cmp / ref / start 的链接时调一次（不用登录）。
 * 访问者用设备号（请求头 X-Device-Id，客户端 {@code fc.device}）的 sha256 区分，不存原文；没有设备号时按 IP 的 sha256。
 * 同一访问者同一天同一来源只记一次；参数都不合法时什么都不记。总是返回 204（不让页面因为统计失败出错）。
 */
@RestController
class VisitController {

  record VisitBody(String src, String cmp, String ref, String start) {}

  private final VisitMapper mapper;
  private final Clock clock;

  VisitController(VisitMapper mapper, Clock clock) {
    this.mapper = mapper;
    this.clock = clock;
  }

  @PostMapping("/api/attribution/visit")
  ResponseEntity<Void> visit(@RequestBody(required = false) VisitBody body, HttpServletRequest req) {
    if (body == null) return ResponseEntity.noContent().build();
    Attribution.Source s = Attribution.of(body.src(), body.cmp(), body.ref(), body.start());
    if (s.isEmpty()) return ResponseEntity.noContent().build();
    String device = req.getHeader("X-Device-Id");
    String key = device != null && device.strip().matches("[A-Za-z0-9_-]{16,64}") ? "d:" + device.strip() : "ip:" + req.getRemoteAddr();
    LocalDateTime now = LocalDateTime.now(clock.withZone(ZoneOffset.UTC)).truncatedTo(ChronoUnit.MILLIS);
    LocalDate day = now.toLocalDate();
    mapper.insertIgnore(day, sha256(key), nz(s.src()), nz(s.cmp()), nz(s.ref()), nz(s.start()), now);
    return ResponseEntity.noContent().build();
  }

  private static String nz(String v) {
    return v == null ? "" : v;
  }

  static String sha256(String v) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(v.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
