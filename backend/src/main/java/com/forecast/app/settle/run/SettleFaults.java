package com.forecast.app.settle.run;

/**
 * 派彩、退款每处理完一批（提交之前）调用一次（forecast S5）。正式环境什么都不做；后端测试换成在第 N 批抛异常的实现，
 * 模拟"结算中途崩溃"（这一批的事务回滚，和进程在提交前死掉一样），再验证续跑的结果和一次跑完相同（架构方案第 17 节）。
 */
public interface SettleFaults {

  /**
   * @param kind PAYOUT / REFUND
   * @param batchIndex 这一次调用里的第几批（从 0 开始；计算和派彩在同一个事务里时，一个事务里有多批）
   */
  void afterBatch(long runId, String kind, int batchIndex);

  /** 正式环境用的：什么都不做（具名类，测试可以用 Mockito 的 spy 包住它） */
  final class None implements SettleFaults {
    @Override
    public void afterBatch(long runId, String kind, int batchIndex) {}
  }
}
