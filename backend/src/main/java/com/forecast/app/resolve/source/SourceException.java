package com.forecast.app.resolve.source;

/** 适配器解析失败：{@link Kind#NOT_READY} 数据还不全（K 线根数不够、OKX 还没收盘），{@link Kind#BAD_FORMAT} 返回格式不对。 */
public class SourceException extends Exception {

  public enum Kind {
    /** 数据还没有：下一轮（1 分钟后）再取，不算数据源故障 */
    NOT_READY,
    /** 返回格式不对（字段缺、不是数字、错误码）：算数据源故障（计入熔断），本轮不重试 */
    BAD_FORMAT
  }

  private final Kind kind;

  public SourceException(Kind kind, String message) {
    super(message);
    this.kind = kind;
  }

  public Kind kind() {
    return kind;
  }

  public static SourceException notReady(String message) {
    return new SourceException(Kind.NOT_READY, message);
  }

  public static SourceException badFormat(String message) {
    return new SourceException(Kind.BAD_FORMAT, message);
  }
}
