package com.forecast.app.wallet;

/**
 * 支持的三条链。ERC20 和 BSC 都是以太坊体系，用同一个地址（需求 4.4-1）。
 * 接口里的名字和客户端 Network 一致：ERC20 / TRC20 / BSC。
 */
public enum Chain {
  ERC20(Family.ETH),
  BSC(Family.ETH),
  TRC20(Family.TRON);

  /** 地址体系：同一体系的链共用一个充值地址 */
  public enum Family { ETH, TRON }

  public final Family family;

  Chain(Family family) {
    this.family = family;
  }
}
