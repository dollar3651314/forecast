package com.forecast.app.common.e2e;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * 端到端测试的保护（S19，测试报告/v1-端到端测试方案.md 第 4 节）。只在 Spring profile {@code e2e} 下存在。
 * <p>
 * e2e profile 会打开 {@code /api/e2e/**}（取验证码、注入充值、推进提现；S5 起还有替身数据源、可调时钟），这些接口不需要登录，绝不能出现在测试环境和生产。
 * 所以在创建任何业务对象（连数据库、跑迁移）之前先检查，有一条不满足就拒绝启动：
 * <ul>
 *   <li>必须同时设置 {@code forecast.e2e.enabled=true}（只带上 profile 不够，防止误带）</li>
 *   <li>不能同时打开 {@code prod}、{@code production}、{@code staging} profile</li>
 *   <li>不能配置热钱包私钥、链节点地址、SMTP 服务器：这些只在测试环境和生产才有，出现了说明是在真实配置上误开了 e2e</li>
 * </ul>
 * 通过后打印醒目的警告。
 */
@Component
@Profile(E2eGuard.PROFILE)
public class E2eGuard implements BeanFactoryPostProcessor, EnvironmentAware {

  public static final String PROFILE = "e2e";
  /** 端到端测试专用接口的路径前缀 */
  public static final String PATH_PREFIX = "/api/e2e/";

  private static final Logger log = LoggerFactory.getLogger(E2eGuard.class);
  private static final List<String> REAL_ONLY = List.of(
      "forecast.wallet.hot.eth-private-key", "forecast.wallet.hot.tron-private-key", "forecast.wallet.sweep.evm.operator-key",
      "forecast.wallet.chains.ERC20.rpc-url", "forecast.wallet.chains.BSC.rpc-url", "forecast.wallet.chains.TRC20.rpc-url",
      "forecast.notify.mail.host");

  private Environment env;

  @Override
  public void setEnvironment(Environment environment) {
    this.env = environment;
  }

  /** 不满足的条件（空表示可以启动） */
  public static List<String> problems(Environment env) {
    List<String> out = new ArrayList<>();
    if (!"true".equalsIgnoreCase(env.getProperty("forecast.e2e.enabled", ""))) out.add("没有设置 forecast.e2e.enabled=true");
    if (env.acceptsProfiles(Profiles.of("prod | production | staging"))) out.add("e2e 不能和 prod、production、staging profile 同时使用");
    for (String key : REAL_ONLY) {
      String v = env.getProperty(key, "");
      if (!v.isBlank()) out.add("配置了 " + key + "（只有测试环境和生产才有）");
    }
    return out;
  }

  @Override
  public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
    List<String> p = problems(env);
    if (!p.isEmpty()) throw new IllegalStateException("拒绝以 e2e profile 启动：" + String.join("；", p));
    log.warn("""

        ************************************************************
        *  端到端测试模式（profile e2e）：/api/e2e/** 已打开，
        *  不需要登录就能注入充值、读验证码。
        *  只能用于 CI 和本机的端到端测试，绝不能用于测试环境和生产。
        ************************************************************""");
  }
}
