package com.forecast.app.common.time;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 应用统一用的时钟（UTC）。测试里可以换成固定时间，避免结果随运行时间变化（例如周末休市）。 */
@Configuration
class ClockConfig {

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
