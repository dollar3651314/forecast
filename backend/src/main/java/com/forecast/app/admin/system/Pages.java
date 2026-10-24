package com.forecast.app.admin.system;

import java.util.List;

/** 后台列表统一分页：页码从 1 开始，每页最多 100 条（修复现有系统 C-10"超过 100 人查不到"：按页查，不是只取前 100 条）。 */
final class Pages {

  /** 字段和原型 Page 一致 */
  record Page<T>(List<T> items, long total, int page, int pageSize) {}

  record Slice(int page, int size, int offset) {}

  private Pages() {}

  static Slice slice(int page, int pageSize) {
    int p = Math.max(1, page);
    int s = Math.min(100, Math.max(1, pageSize));
    return new Slice(p, s, (p - 1) * s);
  }
}
