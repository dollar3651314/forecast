package com.forecast.app.settle.run;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** settle 模块的配置（forecast S5）：{@link SettleProperties}、正式环境的 {@link SettleFaults}（什么都不做）。 */
@Configuration
@EnableConfigurationProperties(SettleProperties.class)
class SettleConfig {

  @Bean
  @ConditionalOnMissingBean
  SettleFaults settleFaults() {
    return new SettleFaults.None();
  }
}
