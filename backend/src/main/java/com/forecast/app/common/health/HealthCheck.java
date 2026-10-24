package com.forecast.app.common.health;

/**
 * 模块自己的健康检查，`GET /api/health` 会逐个调用；任何一个不通过就返回 503。
 * 只返回通过与否，不返回错误详情（详情写日志）。
 */
public interface HealthCheck {

  /** 健康检查结果里的名字，例如 wallet */
  String name();

  boolean ok();
}
