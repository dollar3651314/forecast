package com.forecast.app.common.error;

import java.util.ArrayList;
import java.util.List;

/**
 * 字段校验失败，一次带上全部字段的错误（C56）。接口返回 400 {code: VALIDATION_FAILED, fields: [{field, code}]}。
 * code 和客户端的翻译键一致（例如 lotsMin、tpBuy）。
 */
public class ValidationException extends RuntimeException {

  public record Field(String field, String code) {}

  private final List<Field> fields;

  public ValidationException(List<Field> fields) {
    super("请求字段不合法");
    this.fields = List.copyOf(fields);
  }

  public List<Field> fields() {
    return fields;
  }

  /** 逐个收集字段错误，最后一次抛出。 */
  public static final class Collector {
    private final List<Field> fields = new ArrayList<>();

    public Collector add(String field, String code) {
      fields.add(new Field(field, code));
      return this;
    }

    public boolean has(String field) {
      return fields.stream().anyMatch(f -> f.field().equals(field));
    }

    public void throwIfAny() {
      if (!fields.isEmpty()) throw new ValidationException(fields);
    }
  }
}
