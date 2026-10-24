package com.forecast.app.admin.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 只要登录、不需要权限点的后台接口（当前管理员信息、改密码、退出）。"必须先改密码"时也能调用。 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AdminLoginOnly {}
