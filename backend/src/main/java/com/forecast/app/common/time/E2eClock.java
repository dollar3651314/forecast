package com.forecast.app.common.time;

import com.forecast.app.common.e2e.E2eGuard;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 端到端测试的可调时钟（forecast S3；只在 profile e2e 下存在，{@link E2eGuard} 保护）。
 * 应用里所有业务"现在"都来自注入的 {@link Clock}（技术方案第 2.3 节），e2e 下换成"系统时间 + 偏移量"：时间照常往前走，
 * 测试可以把它整体往后拨，验证到点截止、事件结束（AC-14、AC-15）。后端测试用的是另一个可调时钟（测试代码里的 MutableClock）。
 * <ul>
 *   <li>{@code GET /api/e2e/clock}：现在的时间和偏移量</li>
 *   <li>{@code POST /api/e2e/clock/advance} {@code {"millis": 60000}}：往后拨（不能往回拨）</li>
 *   <li>{@code POST /api/e2e/clock/reset}：回到系统时间</li>
 * </ul>
 * 往后拨会影响所有用时钟的功能（登录会话、后台 30 分钟无操作退出、提现冷静期），测试拨完要 reset。
 */
@Configuration
@Profile(E2eGuard.PROFILE)
class E2eClock {

  /** 系统时间 + 偏移量 */
  static final class OffsetClock extends Clock {

    private final AtomicLong offsetMillis = new AtomicLong();

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return Clock.fixed(instant(), zone);
    }

    @Override
    public Instant instant() {
      return Instant.now().plusMillis(offsetMillis.get());
    }

    long offset() {
      return offsetMillis.get();
    }

    void advance(long millis) {
      offsetMillis.addAndGet(millis);
    }

    void reset() {
      offsetMillis.set(0);
    }
  }

  @Bean
  @Primary
  OffsetClock e2eOffsetClock() {
    return new OffsetClock();
  }

  @RestController
  @Profile(E2eGuard.PROFILE)
  static class E2eClockApi {

    record Advance(Long millis) {}

    private final OffsetClock clock;

    E2eClockApi(OffsetClock clock) {
      this.clock = clock;
    }

    @GetMapping("/api/e2e/clock")
    Map<String, Object> now() {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("now", clock.instant().toString());
      m.put("offsetMillis", clock.offset());
      return m;
    }

    /** 往后拨，最多一次 30 天 */
    @PostMapping("/api/e2e/clock/advance")
    ResponseEntity<Map<String, Object>> advance(@RequestBody Advance body) {
      if (body == null || body.millis() == null || body.millis() <= 0 || body.millis() > Duration.ofDays(30).toMillis()) {
        return ResponseEntity.badRequest().build();
      }
      clock.advance(body.millis());
      return ResponseEntity.ok(now());
    }

    @PostMapping("/api/e2e/clock/reset")
    Map<String, Object> reset() {
      clock.reset();
      return now();
    }
  }
}
