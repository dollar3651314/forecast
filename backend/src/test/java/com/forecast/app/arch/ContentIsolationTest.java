package com.forecast.app.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.forecast.app.market.api.MarketDraftApi;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.EvaluationResult;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * 自动化程序的隔离（forecast S15；需求 NF-15、AC-102、AC-117；架构方案第 18.3 节"没有上架和资金权限"）：content 模块和主应用在同一个进程，
 * 所以用代码边界保证它<b>只能建市场草稿</b>：
 * <ul>
 *   <li>不依赖账本、彩池、结算、钱包、客户、风控、来源统计、后台的任何类（连 api 包都不行）；</li>
 *   <li>市场只能用 {@link MarketDraftApi}（建草稿、查状态）和只读的公开快照、卡片图片；不能用 {@code MarketApi}（状态跳转、结算）、{@code MarketAdminApi}（上架、暂停、取消、改费率）、
 *       {@code MarketAutogenAdminApi}；裁决只能读行情和赛程，不能用 {@code ResultHandler}、{@code ResolveDeskApi}、{@code SportsAdminApi}、{@code ResolveAdminApi}。</li>
 *   <li>{@link MarketDraftApi} 本身没有上架、改状态的方法。</li>
 * </ul>
 */
class ContentIsolationTest {

  static final JavaClasses CLASSES = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("com.forecast.app");

  @Test
  void content不碰资金和别的业务模块() {
    EvaluationResult r = noClasses().that().resideInAPackage("com.forecast.app.content..")
        .should().dependOnClassesThat().resideInAnyPackage("com.forecast.app.ledger..", "com.forecast.app.pool..", "com.forecast.app.settle..",
            "com.forecast.app.wallet..", "com.forecast.app.account..", "com.forecast.app.riskalert..", "com.forecast.app.growth..", "com.forecast.app.admin..")
        .because("自动化程序没有动资金、客户、后台权限的能力（NF-15）").evaluate(CLASSES);
    assertThat(r.hasViolation()).as(r.getFailureReport().toString()).isFalse();
  }

  @Test
  void content只能建市场草稿_不能上架改状态_不能碰裁决和结算() {
    EvaluationResult r = noClasses().that().resideInAPackage("com.forecast.app.content..")
        .should().dependOnClassesThat().haveFullyQualifiedName("com.forecast.app.market.api.MarketApi")
        .orShould().dependOnClassesThat().haveFullyQualifiedName("com.forecast.app.market.api.MarketAdminApi")
        .orShould().dependOnClassesThat().haveFullyQualifiedName("com.forecast.app.market.api.MarketAutogenAdminApi")
        .orShould().dependOnClassesThat().haveFullyQualifiedName("com.forecast.app.market.api.MarketListener")
        .orShould().dependOnClassesThat().haveFullyQualifiedName("com.forecast.app.resolve.api.ResultHandler")
        .orShould().dependOnClassesThat().haveFullyQualifiedName("com.forecast.app.resolve.api.ResolveDeskApi")
        .orShould().dependOnClassesThat().haveFullyQualifiedName("com.forecast.app.resolve.api.SportsAdminApi")
        .orShould().dependOnClassesThat().haveFullyQualifiedName("com.forecast.app.resolve.api.ResolveAdminApi")
        .because("自动化程序只能建草稿，上架要有 market:publish 的人（AC-102）").evaluate(CLASSES);
    // DraftInput、OptionInput 是 MarketAdminApi 里的记录类型，引用它们算依赖 MarketAdminApi 的内部类，不算依赖接口本身
    assertThat(r.hasViolation()).as(r.getFailureReport().toString()).isFalse();
  }

  @Test
  void 建草稿的接口里没有上架和改状态的方法() {
    assertThat(Arrays.stream(MarketDraftApi.class.getMethods()).map(Method::getName)).containsExactlyInAnyOrder("createDraft", "status");
  }
}
