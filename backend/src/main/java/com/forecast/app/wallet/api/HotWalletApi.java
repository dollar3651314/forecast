package com.forecast.app.wallet.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** 热钱包余额（2026-10-17 热钱包余额告警）：后台对账页显示当前余额、阈值、最后查询时间。只读，余额由钱包模块定时查询，不在这里访问节点。 */
public interface HotWalletApi {

  /**
   * 一项余额。
   *
   * @param chain 链：ERC20 / BSC / TRC20
   * @param asset 币种：USDT，或手续费币 ETH / BNB / TRX
   * @param gas 是不是手续费币
   * @param address 热钱包地址（公开地址，不是密钥）
   * @param balance 最近一次查询成功的余额；还没查成功过时为空
   * @param threshold 告警下限（后台系统配置）：余额低于它时告警；<b>0 = 不告警</b>（默认）
   * @param low 下限大于 0 并且余额低于下限（还没查成功过时为 false）
   * @param checkedAt 最近一次查询成功的时间；还没查成功过时为空
   * @param error 最近一次查询失败的原因（最近一次成功时为空），不含节点地址
   */
  record Balance(String chain, String asset, boolean gas, String address, BigDecimal balance, BigDecimal threshold, boolean low, Instant checkedAt,
      String error) {}

  /** 已配置的热钱包各项余额（只列配置了节点、USDT 合约和热钱包地址的链；没有配置时为空列表） */
  List<Balance> hotBalances();
}
