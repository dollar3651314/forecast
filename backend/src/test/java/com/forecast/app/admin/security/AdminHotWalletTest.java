package com.forecast.app.admin.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.forecast.app.common.config.ConfigKey;
import com.forecast.app.common.health.AlertCheck;
import com.forecast.app.support.MutableClock;
import com.forecast.app.wallet.Chain;
import com.forecast.app.wallet.WalletProperties;
import com.forecast.app.wallet.withdraw.HotBalanceMonitor;
import com.forecast.app.wallet.withdraw.HotBalanceProperties;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 热钱包余额告警的下限在后台"系统配置"页设置（Amos 2026-10-17："告警的默认下限让我可以设置，现在设置成零"）：
 * 六项默认 0 = 不告警；超管改成大于 0 后余额低于它就告警，改回 0 告警消失；每次修改是高危操作、写审计；对账页的热钱包余额接口要 recon:view。
 */
class AdminHotWalletTest extends AdminTestSupport {

  static final List<ConfigKey> KEYS = List.of(ConfigKey.HOT_MIN_USDT_ERC20, ConfigKey.HOT_MIN_USDT_BSC, ConfigKey.HOT_MIN_USDT_TRC20, ConfigKey.HOT_MIN_ETH,
      ConfigKey.HOT_MIN_BNB, ConfigKey.HOT_MIN_TRX);

  final ObjectMapper json = new ObjectMapper();

  JsonNode body(MockHttpServletResponse r) throws Exception {
    return json.readTree(r.getContentAsString());
  }

  @Test
  void 六个下限默认0_系统配置页可改_高危写审计_改成大于0告警_改回0告警消失() throws Exception {
    // 替身节点：ERC20 热钱包 812.5 USDT、0.005 ETH
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", ex -> {
      String req = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      String hex = req.contains("eth_call") ? Long.toHexString(812_500_000L) : Long.toHexString(5_000_000_000_000_000L);
      byte[] out = ("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"0x" + hex + "\"}").getBytes(StandardCharsets.UTF_8);
      ex.sendResponseHeaders(200, out.length);
      ex.getResponseBody().write(out);
      ex.close();
    });
    server.start();
    Admin s = superAdmin();
    try {
      WalletProperties wallet = new WalletProperties(null, BigDecimal.ONE, Map.of(Chain.ERC20, new WalletProperties.ChainConfig(
          "http://127.0.0.1:" + server.getAddress().getPort(), "0xdAC17F958D2ee523a2206206994597C13D831ec7", 6, 12, 12, 20, null)));
      // 下限读真实的系统配置（和应用里的 Spring 构造一样）
      HotBalanceMonitor hot = new HotBalanceMonitor(wallet, "0x7E5F4552091A69125d5DfCb7b8C2659029395Bdf", null, HotBalanceProperties.defaults(),
          (c, gas) -> config.decimal(HotBalanceMonitor.configKey(c, gas), BigDecimal.ZERO), new MutableClock(), json);
      hot.refresh();

      // 默认 0（迁移 V25）
      JsonNode list = body(call(get("/api/admin/configs"), s.cookie(), null));
      for (ConfigKey k : KEYS) {
        JsonNode e = null;
        for (JsonNode n : list) if (k.key.equals(n.get("key").asString())) e = n;
        assertThat(e).as(k.key).isNotNull();
        assertThat(e.get("value").asString()).isEqualTo("0");
        assertThat(e.get("group").asString()).isEqualTo("hotWallet");
      }
      assertThat(list.toString()).contains("\"key\":\"hotWallet.minGas.ERC20\",\"value\":\"0\",\"unit\":\"eth\"")
          .contains("\"key\":\"hotWallet.minGas.BSC\",\"value\":\"0\",\"unit\":\"bnb\"").contains("\"key\":\"hotWallet.minGas.TRC20\",\"value\":\"0\",\"unit\":\"trx\"");
      assertThat(hot.evaluate()).as("默认 0：不告警").isEmpty();
      assertThat(hot.hotBalances()).as("余额照常查到").hasSize(2).allMatch(b -> b.balance() != null);

      // 改成大于 0：必须填原因；最多 8 位小数；不能是负数
      assertThat(call(put("/api/admin/configs/hotWallet.minUsdt.ERC20"), s.cookie(), "{\"value\":\"1000\"}").getContentAsString()).contains("\"field\":\"reason\"");
      assertThat(call(put("/api/admin/configs/hotWallet.minUsdt.ERC20"), s.cookie(), "{\"value\":\"-1\",\"reason\":\"x\"}").getStatus()).isEqualTo(400);
      assertThat(call(put("/api/admin/configs/hotWallet.minGas.ERC20"), s.cookie(), "{\"value\":\"0.123456789\",\"reason\":\"x\"}").getStatus()).isEqualTo(400);
      assertThat(call(put("/api/admin/configs/hotWallet.minUsdt.ERC20"), s.cookie(), "{\"value\":\"1000\",\"reason\":\"开始告警\"}").getStatus()).isEqualTo(200);
      assertThat(call(put("/api/admin/configs/hotWallet.minGas.ERC20"), s.cookie(), "{\"value\":\"0.02\",\"reason\":\"开始告警\"}").getStatus()).isEqualTo(200);
      List<AlertCheck.Item> items = hot.evaluate();
      assertThat(items).extracting(AlertCheck.Item::name).containsExactly("hot.ERC20.usdt", "hot.ERC20.gas");
      assertThat(items).noneMatch(AlertCheck.Item::ok);
      assertThat(items.getFirst().detail()).contains("余额 812.5", "低于告警下限 1000");
      assertThat(audits("config.update")).anyMatch(l -> l.equals("SUCCESS " + s.username() + " 开始告警"));
      assertThat(jdbc.queryForObject("SELECT CONCAT(high_risk, before_json, after_json) FROM admin_audit_log WHERE action = 'config.update' AND target = ? "
          + "AND result = 'SUCCESS' ORDER BY id DESC LIMIT 1", String.class, "hotWallet.minUsdt.ERC20")).isEqualTo("1{\"value\":\"0\"}{\"value\":\"1000\"}");

      // 改回 0：告警项消失
      call(put("/api/admin/configs/hotWallet.minUsdt.ERC20"), s.cookie(), "{\"value\":\"0\",\"reason\":\"先不告警\"}");
      assertThat(hot.evaluate()).extracting(AlertCheck.Item::name).containsExactly("hot.ERC20.gas");
      call(put("/api/admin/configs/hotWallet.minGas.ERC20"), s.cookie(), "{\"value\":\"0\",\"reason\":\"先不告警\"}");
      assertThat(hot.evaluate()).isEmpty();
      assertThat(audits("config.update")).filteredOn(l -> l.equals("SUCCESS " + s.username() + " 先不告警")).hasSize(2);
    } finally {
      for (ConfigKey k : KEYS) jdbc.update("UPDATE common_config SET value = '0' WHERE config_key = ?", k.key);
      config.refresh();
      server.stop(0);
    }
  }

  @Test
  void 热钱包余额接口要对账查看权限_测试环境没配热钱包时为空() throws Exception {
    Admin viewer = withRoles(role(Permission.RECON_VIEW));
    MockHttpServletResponse r = call(get("/api/admin/hot-wallets"), viewer.cookie(), null);
    assertThat(r.getStatus()).isEqualTo(200);
    assertThat(r.getContentAsString()).isEqualTo("[]");
    Admin other = withRoles(role(Permission.TASK_VIEW));
    assertThat(call(get("/api/admin/hot-wallets"), other.cookie(), null).getStatus()).isEqualTo(403);
  }
}
