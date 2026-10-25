package com.forecast.app.wallet.address;

/**
 * 一套合约地址参数（方案第 4.13.3 节"新工厂给新客户"）：存在表 {@code wallet_address_scheme}，每个合约充值地址记着自己是第几套（{@code scheme_ver}）。
 * 换任何一个参数 = 新的一套：新客户用新地址，老地址仍然只认老参数，归集老地址时用老的批量合约。老的一套永远不删。
 *
 * @param ver 第几套（从 1 开始；xpub 地址是 0）
 * @param factory BitGo ForwarderFactoryV4
 * @param implementation BitGo ForwarderV4 实现合约
 * @param parent 冷钱包（Safe）
 * @param feeAddress 批量合约 SweepBatcher
 */
public record ForwarderScheme(int ver, String factory, String implementation, String parent, String feeAddress) {

  /** 用户的充值地址 */
  public String addressOf(long userId) {
    return Create2.forwarderAddress(factory, implementation, parent, feeAddress, Create2.salt(userId));
  }
}
