package com.forecast.app.common.id;

import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 对外编号生成器（{@link Snowflake}）。节点号 {@code forecast.id.node}（环境变量 {@code FC_ID_NODE}），单实例默认 1。用系统时钟（不用业务时钟 {@code java.time.Clock} bean：测试里业务时钟是固定的）。 */
@Configuration
class IdConfig {

  @Bean
  Snowflake snowflake(@Value("${forecast.id.node:1}") long node) {
    LoggerFactory.getLogger(Snowflake.class).info("雪花 ID 节点号 {}", node);
    return Snowflake.shared(node);
  }
}
