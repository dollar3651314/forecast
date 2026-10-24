package com.forecast.app.wallet.sweep;

import com.forecast.app.wallet.SweepEvmProperties;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.web3j.crypto.Credentials;

/**
 * 归集的发起账户（方案 SW-8：一个新的以太坊体系账户，ERC20、BSC 共用，不用热钱包，只付手续费）。
 * 启动时用私钥算出地址，和配置的地址核对：<b>对不上、没配、格式不对时只是不能执行归集</b>（后台显示原因、告警项 {@code sweep.config}），
 * 不影响启动，也不进健康检查的 {@code status}（约定 24：否则部署会被回滚）。私钥不进日志和错误信息。
 */
@Component
class SweepOperator {

  private static final Logger log = LoggerFactory.getLogger(SweepOperator.class);

  private final Credentials credentials;
  private final String problem;
  private final String address;

  SweepOperator(SweepEvmProperties p) {
    String addr = blank(p.operatorAddress()) ? null : p.operatorAddress().strip();
    this.address = addr;
    boolean key = !blank(p.operatorKey());
    if (!key && addr == null) {
      credentials = null;
      problem = "没有配置发起账户（FC_SWEEP_EVM_OPERATOR_ADDRESS、FC_SWEEP_EVM_OPERATOR_KEY）";
      return;
    }
    if (!key) {
      credentials = null;
      problem = "配置了发起账户地址，但没有配置私钥（FC_SWEEP_EVM_OPERATOR_KEY）";
      log.warn("归集发起账户：{}", problem);
      return;
    }
    Credentials c;
    try {
      c = Credentials.create(p.operatorKey().strip());
    } catch (RuntimeException e) {
      c = null;
    }
    if (c == null) {
      credentials = null;
      problem = "发起账户私钥格式不对";
    } else if (addr == null) {
      credentials = null;
      problem = "配置了发起账户私钥，但没有配置地址（FC_SWEEP_EVM_OPERATOR_ADDRESS），无法核对";
    } else if (!c.getAddress().equalsIgnoreCase(addr)) {
      credentials = null;
      problem = "发起账户私钥算出的地址和 FC_SWEEP_EVM_OPERATOR_ADDRESS 对不上";
    } else {
      credentials = c;
      problem = null;
      log.info("归集发起账户已核对：{}", addr);
      return;
    }
    log.error("归集发起账户：{}，不能执行归集（不影响其他功能）", problem);
  }

  Optional<Credentials> credentials() {
    return Optional.ofNullable(credentials);
  }

  /** 不能用时的原因；能用时为空 */
  Optional<String> problem() {
    return Optional.ofNullable(problem);
  }

  /** 配置的地址（公开地址）；没配为空 */
  Optional<String> address() {
    return Optional.ofNullable(credentials != null ? credentials.getAddress() : address);
  }

  /** 地址或私钥至少配了一个 */
  boolean anyConfigured() {
    return credentials != null || address != null || (problem != null && !problem.startsWith("没有配置发起账户"));
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }
}
