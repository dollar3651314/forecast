package com.forecast.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** forecast 后端入口：单体应用；第一期单实例部署，代码按多实例写（技术方案/v1-架构方案.md 第 5.3 节）。 */
@SpringBootApplication
public class ForecastApplication {

  public static void main(String[] args) {
    SpringApplication.run(ForecastApplication.class, args);
  }
}
