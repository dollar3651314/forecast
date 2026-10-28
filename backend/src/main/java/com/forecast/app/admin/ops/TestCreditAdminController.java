package com.forecast.app.admin.ops;

import com.forecast.app.admin.security.AdminContext;
import com.forecast.app.admin.security.Permission;
import com.forecast.app.admin.security.RequiresPermission;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 测试入金（forecast S17A）的后台接口。<b>只在测试环境注册</b>：条件是现有的环境标识 {@code forecast.env-label}（环境变量 {@code FC_ENV_LABEL}）
 * 等于 {@code test}——部署工具包的 {@code deploy/kit/docker-compose.yml} 给测试环境固定写了它，生产不设。生产上这个类不存在，
 * {@code POST /api/admin/customers/{id}/test-credit} 是 404（和任何不存在的路径一样）；没有另外的开关，不会在生产上被误开。
 * 服务层（{@code ledger.api.TestCreditApi}）和 {@link CustomerOps#testCredit} 再各检查一次。
 */
@RestController
@ConditionalOnProperty(prefix = "forecast", name = "env-label", havingValue = "test")
class TestCreditAdminController {

  private final CustomerOps customers;

  TestCreditAdminController(CustomerOps customers) {
    this.customers = customers;
  }

  /** {"amount":"1000","reason":"…","requestId":"…","checked":true}：给客户的可用余额入一笔测试金（高危，单笔上限见参数设置） */
  @RequiresPermission(Permission.BALANCE_TEST_CREDIT)
  @PostMapping("/api/admin/customers/{id}/test-credit")
  CustomerOps.TestCreditResult credit(@PathVariable long id, @RequestBody(required = false) CustomerOps.TestCreditBody body) {
    return customers.testCredit(AdminContext.current(), id, body);
  }
}
