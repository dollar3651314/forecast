package com.forecast.app.arch;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import java.util.List;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 模块边界规则（技术方案/v1-架构方案.md 第 2.3 节，沿用 复制源）：模块 A 的代码不能依赖模块 B 里 {@code api} 包以外的类。
 * 规则按根包名生成，正式代码和测试里的反例共用同一套规则。
 */
final class ModuleRules {

  /** 第 2.3 节的全部模块；market、pool、resolve、settle、riskalert 在 S3 以后才有代码，规则先写上（没有代码时照样成立） */
  static final List<String> MODULES = List.of("ledger", "account", "wallet", "notification", "admin", "market", "pool", "resolve", "settle", "riskalert", "growth");

  private ModuleRules() {}

  static EvaluationResult check(String root, JavaClasses classes) {
    EvaluationResult result = null;
    for (String from : MODULES) {
      for (String to : MODULES) {
        if (from.equals(to)) continue;
        ArchRule rule = noClasses()
            .that().resideInAPackage(root + "." + from + "..")
            .should().dependOnClassesThat(internalOf(root, to))
            .because(from + " 模块只能调用 " + to + " 模块的 api 包")
            .allowEmptyShould(true); // 模块还没有代码时规则照样成立
        EvaluationResult r = rule.evaluate(classes);
        result = result == null ? r : merge(result, r);
      }
    }
    return result;
  }

  private static DescribedPredicate<JavaClass> internalOf(String root, String module) {
    String base = root + "." + module;
    String api = base + ".api";
    return DescribedPredicate.describe(module + " 模块 api 包以外的类", c -> {
      String p = c.getPackageName();
      return (p.equals(base) || p.startsWith(base + ".")) && !(p.equals(api) || p.startsWith(api + "."));
    });
  }

  private static EvaluationResult merge(EvaluationResult a, EvaluationResult b) {
    a.add(b);
    return a;
  }
}
