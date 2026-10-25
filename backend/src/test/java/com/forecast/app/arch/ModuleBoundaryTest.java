package com.forecast.app.arch;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/** 模块之间只通过对方的 api 包调用。违反就 CI 失败，AI 改代码时不会悄悄把模块搅在一起。 */
class ModuleBoundaryTest {

  @Test
  void 正式代码遵守模块边界() {
    JavaClasses classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("com.forecast.app");
    var result = ModuleRules.check("com.forecast.app", classes);
    assertThat(result.hasViolation()).as(result.getFailureReport().toString()).isFalse();
  }

  @Test
  void 规则能拦住跨模块直接调用内部类() {
    JavaClasses classes = new ClassFileImporter().importPackages("archfixture.bad");
    var result = ModuleRules.check("archfixture.bad", classes);
    assertThat(result.hasViolation()).isTrue();
    assertThat(result.getFailureReport().toString()).contains("archfixture.bad.pool.PlaceBet").contains("archfixture.bad.account.internal.AccountRepository");
  }

  @Test
  void 通过对方的api包调用是允许的() {
    JavaClasses classes = new ClassFileImporter().importPackages("archfixture.good");
    assertThat(ModuleRules.check("archfixture.good", classes).hasViolation()).isFalse();
  }
}
