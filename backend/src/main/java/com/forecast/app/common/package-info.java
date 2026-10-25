/**
 * 各模块共用的基础代码，所有模块都可以依赖：金额（{@code money}）、对外编号的雪花 ID（{@code id}）、业务错误（{@code error}）、
 * 追踪号 traceId（{@code trace}：每个 HTTP 请求、WebSocket 连接、定时任务一个，写进日志 MDC、响应头 {@code X-Request-Id} 和错误响应体）、
 * 健康检查和业务告警的接口（{@code health}）、任务表（{@code task}）、限流（{@code ratelimit}）、系统配置（{@code config}）、
 * 时钟（{@code time}）、对象存储（{@code storage}）、Web 公共部分（{@code web}）。
 */
package com.forecast.app.common;
