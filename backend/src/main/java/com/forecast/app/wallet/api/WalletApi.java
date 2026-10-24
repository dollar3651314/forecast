package com.forecast.app.wallet.api;

/** 钱包对其他模块的接口。 */
public interface WalletApi {

  /**
   * 给用户分配充值地址（以太坊体系一个，ERC20 和 BSC 共用；TRON 一个）。已经分配过的不重复分配。
   * 注册时在注册事务里调用（需求 4.4-1）；服务器还没有配置 xpub 时什么也不做，第一次打开资产页时补分配。
   */
  void allocateAddresses(long userId);
}
