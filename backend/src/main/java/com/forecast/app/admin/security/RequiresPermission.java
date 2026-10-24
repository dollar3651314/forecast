package com.forecast.app.admin.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 后台接口需要的权限点。每个 {@code /api/admin/} 接口都必须有这个注解、{@link AdminLoginOnly} 或 {@link AdminPublic} 之一，
 * 都没有的一律返回 403（默认拒绝），测试扫描全部后台接口核对。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresPermission {
  Permission value();
}
