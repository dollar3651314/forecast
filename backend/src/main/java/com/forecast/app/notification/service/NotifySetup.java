package com.forecast.app.notification.service;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 通知模块的配置。邮件发送失败不放进健康检查：健康检查不通过会让整个服务被判为不可用，
 * 不能因为邮件服务商故障影响交易；失败看通知记录和日志（警告级别）。
 */
@Configuration
@EnableConfigurationProperties(NotifyProperties.class)
class NotifySetup {}
