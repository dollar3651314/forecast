package com.forecast.app.admin.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 后台接口 {@code /api/admin/**} 都经过 {@link AdminInterceptor}。 */
@Configuration
@EnableConfigurationProperties(AdminProperties.class)
class AdminWebConfig implements WebMvcConfigurer {

  private final AdminInterceptor interceptor;

  AdminWebConfig(AdminInterceptor interceptor) {
    this.interceptor = interceptor;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(interceptor).addPathPatterns("/api/admin/**");
  }
}
