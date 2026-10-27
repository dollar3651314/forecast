package com.forecast.app.notification.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.forecast.app.common.health.AlertCheck;
import com.forecast.app.common.health.AlertMailSwitch;
import com.forecast.app.common.health.AlertProperties;
import com.forecast.app.notification.api.MailApi;
import com.forecast.app.support.MutableClock;
import com.forecast.app.wallet.Chain;
import com.forecast.app.wallet.WalletProperties;
import com.forecast.app.wallet.withdraw.HotBalanceMonitor;
import com.forecast.app.wallet.withdraw.HotBalanceProperties;
import com.sun.net.httpserver.HttpServer;
import jakarta.mail.internet.MimeMessage;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.ObjectMapper;

/**
 * 热钱包余额告警的邮件内容（2026-10-17）：替身节点上 ERC20 热钱包只有 812.5 USDT、下限 1000 → 告警检查 → 邮件任务（OPS_ALERT）→ SMTP（GreenMail）。
 * 邮件里写清楚哪条链、哪种币、当前余额、下限、热钱包地址；补钱后发"已恢复"。
 */
class HotBalanceMailTest extends NotifyTestSupport {

  static final String ETH_HOT = "0x7E5F4552091A69125d5DfCb7b8C2659029395Bdf";

  @Autowired MailApi mailApi;

  @Test
  void 余额低于下限时告警邮件写清链_币_余额_下限_地址_补钱后发恢复邮件() throws Exception {
    AtomicLong usdt = new AtomicLong(812_500_000L);
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", ex -> {
      String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      String hex = body.contains("eth_call") ? Long.toHexString(usdt.get()) : "de0b6b3a7640000"; // ETH 1.0
      byte[] out = ("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"0x" + hex + "\"}").getBytes(StandardCharsets.UTF_8);
      ex.sendResponseHeaders(200, out.length);
      ex.getResponseBody().write(out);
      ex.close();
    });
    server.start();
    try {
      String url = "http://127.0.0.1:" + server.getAddress().getPort();
      WalletProperties wallet = new WalletProperties(null, BigDecimal.ONE,
          Map.of(Chain.ERC20, new WalletProperties.ChainConfig(url, "0xdAC17F958D2ee523a2206206994597C13D831ec7", 6, 12, 12, 20, null)));
      MutableClock clock = new MutableClock();
      HotBalanceMonitor hot = new HotBalanceMonitor(wallet, ETH_HOT, null, HotBalanceProperties.defaults(),
          (c, gas) -> gas ? new BigDecimal("0.02") : new BigDecimal("1000"), clock, new ObjectMapper());
      String ops = "ops" + System.nanoTime() + "@example.com";
      AlertProperties d = AlertProperties.defaults();
      AlertProperties props = d.with(true, List.of(ops));
      AlertMonitor monitor = new AlertMonitor(List.<AlertCheck>of(hot), props, mailApi, AlertMailSwitch.ALL, clock);

      hot.refresh();
      monitor.check();
      worker.drain();
      List<MimeMessage> mails = mailsTo(ops);
      assertThat(mails).as("只有 USDT 低（ETH 1.0 ≥ 0.02）").hasSize(1);
      assertThat(mails.getFirst().getSubject()).contains("告警：ERC20 热钱包 USDT 余额不足").contains("Alert: ERC20 hot wallet USDT balance low");
      assertThat(body(mails.getFirst())).contains("ERC20 链热钱包 USDT 余额 812.5", "低于告警下限 1000", "热钱包地址 " + ETH_HOT, "请从冷钱包补充");

      usdt.set(5_000_000_000L);
      clock.advance(Duration.ofMinutes(5));
      hot.refresh();
      monitor.check();
      worker.drain();
      assertThat(mailsTo(ops)).hasSize(2);
      assertThat(mailsTo(ops).get(1).getSubject()).contains("已恢复：ERC20 热钱包 USDT 余额不足");
    } finally {
      server.stop(0);
    }
  }
}
