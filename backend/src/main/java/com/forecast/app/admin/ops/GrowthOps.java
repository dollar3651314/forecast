package com.forecast.app.admin.ops;

import com.forecast.app.admin.security.Audit;
import com.forecast.app.growth.api.GrowthAdminApi;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 后台"来源统计"（forecast S13，MF-1、MF-10）：数据在 growth 模块（{@link GrowthAdminApi}），这里负责导出的审计
 * （谁、什么时候、导出了哪一种、什么范围；导出的文件只有客户编号，没有邮箱、地址、KYC 信息，AC-94）。
 */
@Service
class GrowthOps {

  private final GrowthAdminApi growth;
  private final Audit audit;

  GrowthOps(GrowthAdminApi growth, Audit audit) {
    this.growth = growth;
    this.audit = audit;
  }

  GrowthAdminApi.Report report(String from, String to, String basis) {
    return growth.report(from, to, basis);
  }

  String export(String kind, String from, String to, String basis, String lang) {
    return audit.run("growth.export", kind == null ? "-" : kind, false, null, () -> {
      String csv = growth.csv(kind, from, to, basis, lang);
      Map<String, Object> after = new LinkedHashMap<>();
      after.put("kind", kind);
      after.put("from", from);
      after.put("to", to);
      after.put("basis", basis == null ? "first" : basis);
      after.put("lines", csv.chars().filter(c -> c == '\n').count());
      return Audit.Change.of(csv, null, after);
    });
  }
}
