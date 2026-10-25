package com.forecast.app.common.job;

import org.springframework.context.ApplicationContext;

/** 测试用：在同一个库上造出"另一个实例"的租约（不同的实例名），模拟多实例部署。 */
public final class TestLeases {

  private TestLeases() {}

  public static JobLease instance(ApplicationContext ctx, String owner) {
    return new JobLease(ctx.getBean(JobLeaseMapper.class), owner);
  }
}
