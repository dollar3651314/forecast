package com.forecast.app.wallet.chain;

/**
 * 访问区块链节点出错。两种情况决定提现能不能退款：
 * <ul>
 *   <li>{@link Rejected}：节点明确拒绝（JSON-RPC 返回 error、TRON 返回 result=false），交易肯定没有进内存池</li>
 *   <li>{@link Unreachable}：连不上、超时、HTTP 5xx——不知道交易有没有发出去，<b>不能当作失败退款</b></li>
 * </ul>
 */
public abstract class NodeException extends RuntimeException {

  NodeException(String message, Throwable cause) {
    super(message, cause);
  }

  public static final class Rejected extends NodeException {
    public Rejected(String message) {
      super(message, null);
    }
  }

  public static final class Unreachable extends NodeException {
    public Unreachable(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
