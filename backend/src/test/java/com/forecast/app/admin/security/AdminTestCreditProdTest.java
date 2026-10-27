package com.forecast.app.admin.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.forecast.app.common.error.BizException;
import com.forecast.app.ledger.api.LedgerApi;
import com.forecast.app.ledger.api.TestCreditApi;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * forecast S17A 测试入金在<b>生产配置</b>下不存在。生产不设 {@code FC_ENV_LABEL}（部署方案：只有测试环境的 compose 写了 test），
 * 默认的测试配置就是这种情况（application.yml 的 {@code forecast.env-label} 默认空）：接口是 404（超管也一样，和任何不存在的路径一样），
 * 客户详情的 {@code testCredit} 为空（后台据此不显示按钮），服务层也拒绝。和其他后台测试共用默认的 Spring 上下文。
 */
class AdminTestCreditProdTest extends AdminTestSupport {

  @Autowired LedgerApi ledger;
  @Autowired TestCreditApi testCredit;
  @Autowired ApplicationContext ctx;
  final ObjectMapper json = new ObjectMapper();

  long customer() {
    String email = unique("tcp") + "@example.com";
    jdbc.update("INSERT INTO account_user (email, status) VALUES (?, 'ACTIVE')", email);
    long id = jdbc.queryForObject("SELECT id FROM account_user WHERE email = ?", Long.class, email);
    ledger.openAccount(id);
    return id;
  }

  @Test
  void 生产配置_接口404_详情没有测试入金_按钮不显示_服务层也拒绝() throws Exception {
    assertThat(ctx.getEnvironment().getProperty("forecast.env-label", "")).as("和生产一样没有设 FC_ENV_LABEL").isEmpty();
    Admin s = superAdmin();
    long uid = customer();
    MockHttpServletResponse r = call(post("/api/admin/customers/" + uid + "/test-credit"), s.cookie(),
        "{\"amount\":\"100\",\"reason\":\"生产上不能用\",\"requestId\":\"req-" + UUID.randomUUID() + "\",\"checked\":true}");
    assertThat(r.getStatus()).as("生产上接口不存在").isEqualTo(404);
    assertThat(json.readTree(r.getContentAsString()).get("code").asString()).isEqualTo("NOT_FOUND");
    assertThat(call(post("/api/admin/customers/" + uid + "/test-credit"), null, "{}").getStatus()).as("没登录也是 404，不暴露接口存在").isEqualTo(404);
    assertThat(ctx.getBeanNamesForType(Class.forName("com.forecast.app.admin.ops.TestCreditAdminController"))).as("控制器没有注册").isEmpty();

    JsonNode d = json.readTree(call(get("/api/admin/customers/" + uid), s.cookie(), null).getContentAsString());
    assertThat(d.has("testCredit")).isTrue();
    assertThat(d.get("testCredit").isNull()).as("后台按它决定显不显示按钮：生产为空，不显示").isTrue();

    assertThat(testCredit.enabled()).isFalse();
    assertThatThrownBy(() -> testCredit.credit(uid, BigDecimal.TEN, "直接调服务", "req-" + UUID.randomUUID(), s.id()))
        .isInstanceOf(BizException.class).extracting(e -> ((BizException) e).code()).isEqualTo("NOT_FOUND");
    assertThat(ledger.snapshot(uid).available()).isEqualByComparingTo("0");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_test_credit WHERE user_id = ?", Integer.class, uid)).isZero();
    assertThat(audits("customer.test_credit")).as("接口不存在，不会有审计").noneMatch(l -> l.contains(s.username()));
  }
}
