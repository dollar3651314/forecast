package com.forecast.app.common.schedule;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 打开定时任务（@Scheduled）。各定时任务的开关在自己模块的配置里。 */
@Configuration
@EnableScheduling
class SchedulingConfig {}
