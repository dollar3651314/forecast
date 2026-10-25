package com.forecast.app.common.error;

/** 业务错误：带错误码，接口层统一翻译成客户能看懂的原因（不暴露内部信息）。 */
public class BizException extends RuntimeException {

  private final String code;

  public BizException(String code, String message) {
    super(message);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
