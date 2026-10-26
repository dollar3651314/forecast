package com.forecast.app.wallet.withdraw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.forecast.app.common.error.BizException;
import com.forecast.app.common.error.ValidationException;
import com.forecast.app.notification.api.MailApi;
import com.forecast.app.support.FixedClockConfig;
import com.forecast.app.support.MutableClock;
import com.forecast.app.ledger.api.Bucket;
import com.forecast.app.ledger.api.LedgerApi;
import com.forecast.app.ledger.api.LedgerCommand;
import com.forecast.app.ledger.api.LedgerEntryType;
import com.forecast.app.wallet.Chain;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import org.mockito.ArgumentCaptor;
import jakarta.servlet.http.Cookie;
import java.util.Map;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

/** 提现（S13a），真实 MySQL（C37）。签名广播用替身；时间用可以往前拨的测试时钟。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FixedClockConfig.class)
class WithdrawFlowTest {

  static final String ETH_ADDR = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed";
  static final String TRON_ADDR = "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t";

  @Autowired WithdrawService withdraws;
  @Autowired WhitelistService whitelist;
  @Autowired WithdrawSenders senders;
  @Autowired LedgerApi ledger;
  @Autowired JdbcTemplate jdbc;
  @Autowired Clock clock;
  @Autowired MockMvc mvc;
  @MockitoBean MailApi mail;

  /** 记下发过的单，按设定返回结果 */
  static class FakeSender implements WithdrawSender {
    final List<Order> sent = new CopyOnWriteArrayList<>();
    volatile Outcome next = new Sent("0xtx");

    @Override
    public Outcome send(Order order) {
      sent.add(order);
      return next instanceof Sent ? new Sent("0xtx" + order.withdrawalId()) : next;
    }
  }

  @AfterEach
  void reset() {
    ((MutableClock) clock).reset();
    for (Chain c : Chain.values()) senders.remove(c);
  }

  MutableClock time() {
    return (MutableClock) clock;
  }

  long user(String deposit, boolean kyc) {
    long uid = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) + 1 + FLOOR(RAND() * 1000) FROM account_user", Long.class);
    jdbc.update("INSERT INTO account_user (id, email, status) VALUES (?, ?, 'ACTIVE')", uid, "wd" + uid + "@example.com");
    ledger.openAccount(uid);
    if (new BigDecimal(deposit).signum() > 0) {
      ledger.apply(new LedgerCommand(uid, Bucket.AVAILABLE, new BigDecimal(deposit), LedgerEntryType.DEPOSIT, "TEST", "dep-" + uid, "测试入金"));
    }
    if (kyc) {
      jdbc.update("INSERT INTO account_kyc_application (user_id, doc_type, doc_number, front_key, back_key, selfie_key, status) VALUES (?, 'PASSPORT', ?, 'f', 'b', 's', 'APPROVED')",
          uid, "P" + uid);
    }
    return uid;
  }

  /** 加一个已经生效的白名单地址 */
  long activeAddress(long uid, String network, String address) {
    WhitelistRow r = whitelist.add(uid, network, address, "测试");
    jdbc.update("UPDATE wallet_whitelist SET active_at = created_at - INTERVAL 1 HOUR WHERE id = ?", r.id);
    return r.id;
  }

  @Autowired com.forecast.app.common.config.SystemConfig sysConfig;

  String code(Runnable r) {
    try {
      r.run();
      return "OK";
    } catch (BizException e) {
      return e.code();
    }
  }

  BigDecimal bal(long uid) {
    return ledger.snapshot(uid).balance();
  }

  BigDecimal frozen(long uid) {
    return ledger.snapshot(uid).withdrawFrozen();
  }

  String status(long id) {
    return jdbc.queryForObject("SELECT status FROM wallet_withdrawal WHERE id = ?", String.class, id);
  }

  void ledgerConsistent(long uid) {
    for (String[] b : new String[][] {{"AVAILABLE", "available"}, {"BET_LOCKED", "bet_locked"}, {"WITHDRAW_FROZEN", "withdraw_frozen"}}) {
      BigDecimal sum = jdbc.queryForObject("SELECT COALESCE(SUM(l.amount), 0) FROM ledger_entry l JOIN ledger_account a ON a.id = l.account_id WHERE a.user_id = ? AND l.bucket = ?",
          BigDecimal.class, uid, b[0]);
      BigDecimal col = jdbc.queryForObject("SELECT " + b[1] + " FROM ledger_account WHERE user_id = ?", BigDecimal.class, uid);
      assertThat(sum).as("账本合计 = " + b[1]).isEqualByComparingTo(col);
    }
  }

  @Test
  void 白名单_24小时生效_地址格式_去重_最多10个() {
    long uid = user("0", false);
    WhitelistRow r = whitelist.add(uid, "ERC20", ETH_ADDR, "我的钱包");
    assertThat(Duration.between(r.createdAt, r.activeAt)).isEqualTo(Duration.ofHours(24));
    assertThat(code(() -> whitelist.add(uid, "ERC20", ETH_ADDR.toLowerCase(), "重复"))).isEqualTo("WHITELIST_DUPLICATE");
    assertThat(code(() -> whitelist.add(uid, "BSC", ETH_ADDR, "BSC 是另一条链"))).isEqualTo("OK");
    assertThat(code(() -> whitelist.add(uid, "TRC20", TRON_ADDR, "波场"))).isEqualTo("OK");
    assertThatThrownBy(() -> whitelist.add(uid, "TRC20", "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6x", "校验和错"))
        .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.fields()).containsExactly(new ValidationException.Field("address", "addressInvalid")));
    assertThatThrownBy(() -> whitelist.add(uid, "ERC20", "0x123", ""))
        .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.fields()).extracting(ValidationException.Field::field).containsExactlyInAnyOrder("address", "label"));
    for (int i = 0; i < 7; i++) whitelist.add(uid, "ERC20", String.format("0x%040d", i), "第" + i + "个");
    assertThat(code(() -> whitelist.add(uid, "ERC20", String.format("0x%040d", 99), "第 11 个"))).isEqualTo("WHITELIST_FULL");
  }

  @Test
  void 六种拒绝原因_AC17_加上可提金额不足() {
    long noKyc = user("50000", false);
    long w0 = activeAddress(noKyc, "ERC20", ETH_ADDR);
    assertThat(code(() -> withdraws.submit(noKyc, String.valueOf(w0), "100"))).isEqualTo("KYC_REQUIRED");

    long uid = user("50000", true);
    long active = activeAddress(uid, "ERC20", ETH_ADDR);
    long fresh = whitelist.add(uid, "TRC20", TRON_ADDR, "刚加的").id;
    assertThat(code(() -> withdraws.submit(uid, String.valueOf(w0), "100"))).as("别人的白名单").isEqualTo("NOT_WHITELISTED");
    assertThat(code(() -> withdraws.submit(uid, String.valueOf(fresh), "100"))).as("未满 24 小时").isEqualTo("WHITELIST_NOT_ACTIVE");
    assertThat(code(() -> withdraws.submit(uid, String.valueOf(active), "99.99"))).as("单笔最小提现默认 100（RQ-16、AC-61）").isEqualTo("AMOUNT_BELOW_MIN");
    assertThat(code(() -> withdraws.submit(uid, String.valueOf(active), "10000.01"))).isEqualTo("AMOUNT_ABOVE_MAX");
    assertThat(code(() -> withdraws.submit(uid, String.valueOf(active), "10000"))).isEqualTo("OK");
    assertThat(code(() -> withdraws.submit(uid, String.valueOf(active), "10000"))).isEqualTo("OK");
    assertThat(code(() -> withdraws.submit(uid, String.valueOf(active), "10000"))).as("累计到 30,000（必须小于）").isEqualTo("DAILY_LIMIT_EXCEEDED");
    assertThat(code(() -> withdraws.submit(uid, String.valueOf(active), "9999"))).isEqualTo("OK");

    long poor = user("150", true);
    long pa = activeAddress(poor, "ERC20", ETH_ADDR);
    assertThat(code(() -> withdraws.submit(poor, String.valueOf(pa), "160"))).isEqualTo("INSUFFICIENT_BALANCE");
    assertThatThrownBy(() -> withdraws.submit(poor, null, "1.234"))
        .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.fields()).extracting(ValidationException.Field::field).containsExactlyInAnyOrder("whitelistId", "amount"));
  }

  @Test
  void 单笔最小提现_默认100_后台改成120后立即生效_AC61() {
    long uid = user("10000", true);
    long a = activeAddress(uid, "ERC20", ETH_ADDR);
    assertThat(code(() -> withdraws.submit(uid, String.valueOf(a), "99.99"))).isEqualTo("AMOUNT_BELOW_MIN");
    assertThat(code(() -> withdraws.submit(uid, String.valueOf(a), "100"))).isEqualTo("OK");
    try {
      jdbc.update("UPDATE common_config SET value = '120' WHERE config_key = 'withdraw.minAmount'");
      sysConfig.refresh();
      assertThat(code(() -> withdraws.submit(uid, String.valueOf(a), "119.99"))).isEqualTo("AMOUNT_BELOW_MIN");
      assertThat(code(() -> withdraws.submit(uid, String.valueOf(a), "120"))).isEqualTo("OK");
    } finally {
      jdbc.update("UPDATE common_config SET value = '100' WHERE config_key = 'withdraw.minAmount'");
      sysConfig.refresh();
    }
  }

  @Test
  void 下注锁定的钱不能提现_S2() {
    long uid = user("200", true);
    long a = activeAddress(uid, "ERC20", ETH_ADDR);
    ledger.applyAll(List.of(
        new LedgerCommand(uid, Bucket.AVAILABLE, new BigDecimal("-150"), LedgerEntryType.BET_LOCK, "BET", "wd-bet-" + uid, "下注", 1L),
        new LedgerCommand(uid, Bucket.BET_LOCKED, new BigDecimal("150"), LedgerEntryType.BET_LOCK, "BET", "wd-bet-" + uid, "下注", 1L)));
    assertThat(code(() -> withdraws.submit(uid, String.valueOf(a), "100"))).as("余额 200，其中 150 在下注中，可提只有 50").isEqualTo("INSUFFICIENT_BALANCE");
    ledger.applyAll(List.of(
        new LedgerCommand(uid, Bucket.BET_LOCKED, new BigDecimal("-150"), LedgerEntryType.REFUND, "REFUND", "wd-ref-" + uid, "退回", 1L),
        new LedgerCommand(uid, Bucket.AVAILABLE, new BigDecimal("150"), LedgerEntryType.REFUND, "REFUND", "wd-ref-" + uid, "退回", 1L)));
    assertThat(code(() -> withdraws.submit(uid, String.valueOf(a), "100"))).as("退回以后可以提").isEqualTo("OK");
    assertThat(ledger.snapshot(uid).available()).isEqualByComparingTo("100");
    assertThat(ledger.snapshot(uid).withdrawFrozen()).isEqualByComparingTo("100");
    ledgerConsistent(uid);
  }

  @Test
  void 提交冻结_冷静期内撤销立即退回_过了冷静期不能撤销_AC18() {
    long uid = user("1000", true);
    long a = activeAddress(uid, "ERC20", ETH_ADDR);
    WithdrawalRow w = withdraws.submit(uid, String.valueOf(a), "100");
    assertThat(w.status).isEqualTo("COOLING");
    assertThat(w.fee).isEqualByComparingTo("5");
    assertThat(Duration.between(w.createdAt, w.coolingEndsAt)).isEqualTo(Duration.ofHours(2));
    assertThat(bal(uid)).isEqualByComparingTo("900");
    assertThat(frozen(uid)).isEqualByComparingTo("100");
    withdraws.cancel(uid, w.id);
    assertThat(status(w.id)).isEqualTo("CANCELLED");
    assertThat(bal(uid)).isEqualByComparingTo("1000");
    assertThat(frozen(uid)).isEqualByComparingTo("0");

    WithdrawalRow w2 = withdraws.submit(uid, String.valueOf(a), "100");
    time().advance(Duration.ofHours(2).plusSeconds(1));
    assertThat(code(() -> withdraws.cancel(uid, w2.id))).isEqualTo("WITHDRAW_NOT_CANCELLABLE");
    withdraws.advance();
    assertThat(status(w2.id)).as("冷静期结束进入待审核").isEqualTo("REVIEWING");
    ledgerConsistent(uid);
    assertThat(notices(w.id)).as("客户自己撤销不通知").isEmpty();
    assertThat(notices(w2.id)).as("冷静期结束不通知").isEmpty();
  }

  /**
   * 这笔提现通知客户的状态（通知方案 N6），按时间先后。
   * 同时按客户筛选：NotifyFlowTest 给别的客户发过 orderId = "88" 的提现通知，这里的提现正好是 88 号时会混进来（CI 偶发失败，2026-10-21）。
   * 通知里的 orderId 是对外编号 public_no（体验优化 1021 UX-11，和客户端提现记录上显示的一致），不是内部 id
   */
  List<String> notices(long withdrawalId) {
    return jdbc.queryForList("SELECT JSON_UNQUOTE(JSON_EXTRACT(params, '$.status')) FROM notify_message WHERE template_code = 'WITHDRAW_STATUS' "
        + "AND JSON_UNQUOTE(JSON_EXTRACT(params, '$.orderId')) = (SELECT CAST(public_no AS CHAR) FROM wallet_withdrawal WHERE id = ?) "
        + "AND user_id = (SELECT user_id FROM wallet_withdrawal WHERE id = ?) ORDER BY id",
        String.class, withdrawalId, withdrawalId);
  }

  WithdrawalRow reviewing(long uid, long address, String amount) {
    WithdrawalRow w = withdraws.submit(uid, String.valueOf(address), amount);
    time().advance(Duration.ofHours(3));
    withdraws.advance();
    assertThat(status(w.id)).isEqualTo("REVIEWING");
    return w;
  }

  @Test
  void 小额审核通过_广播_确认后完成_链上转出金额减手续费() {
    FakeSender s = new FakeSender();
    senders.register(Chain.TRC20, s);
    long uid = user("1000", true);
    long a = activeAddress(uid, "TRC20", TRON_ADDR);
    WithdrawalRow w = reviewing(uid, a, "100");
    withdraws.approve(w.id, 9L);
    assertThat(status(w.id)).as("审核通过立刻广播").isEqualTo("BROADCASTING");
    assertThat(s.sent).singleElement().satisfies(o -> {
      assertThat(o.sendAmount()).isEqualByComparingTo("99");
      assertThat(o.address()).isEqualTo(TRON_ADDR);
    });
    assertThat(jdbc.queryForObject("SELECT tx_hash FROM wallet_withdrawal WHERE id = ?", String.class, w.id)).isEqualTo("0xtx" + w.id);
    withdraws.confirmed(w.id);
    assertThat(status(w.id)).isEqualTo("COMPLETED");
    assertThat(notices(w.id)).containsExactly("APPROVED", "BROADCASTING", "COMPLETED");
    assertThat(bal(uid)).isEqualByComparingTo("900");
    assertThat(frozen(uid)).isEqualByComparingTo("0");
    ledgerConsistent(uid);
  }

  @Test
  void 驳回必须填原因_冻结退回() {
    long uid = user("1000", true);
    long a = activeAddress(uid, "ERC20", ETH_ADDR);
    WithdrawalRow w = reviewing(uid, a, "100");
    assertThatThrownBy(() -> withdraws.reject(w.id, 9L, " ")).isInstanceOf(ValidationException.class);
    withdraws.reject(w.id, 9L, "地址存在风险");
    assertThat(status(w.id)).isEqualTo("REJECTED");
    assertThat(notices(w.id)).containsExactly("REJECTED");
    assertThat(jdbc.queryForObject("SELECT params FROM notify_message WHERE template_code = 'WITHDRAW_STATUS' AND params LIKE ?", String.class,
        "%\"orderId\":\"" + w.publicNo + "\"%")).as("驳回原因告诉客户").contains("地址存在风险");
    assertThat(w.publicNo).as("通知里的编号是对外编号，不是内部 id").isNotEqualTo(w.id);
    assertThat(bal(uid)).isEqualByComparingTo("1000");
    assertThat(code(() -> withdraws.approve(w.id, 9L))).as("不能再审核").isEqualTo("WITHDRAW_NOT_REVIEWABLE");
  }

  @Test
  void 大额延迟6小时_到期直接上链_F1() {
    FakeSender s = new FakeSender();
    senders.register(Chain.ERC20, s);
    long uid = user("5000", true);
    long a = activeAddress(uid, "ERC20", ETH_ADDR);
    WithdrawalRow w = reviewing(uid, a, "3000");
    withdraws.approve(w.id, 9L);
    assertThat(status(w.id)).isEqualTo("DELAYED");
    time().advance(Duration.ofHours(5));
    withdraws.advance();
    assertThat(status(w.id)).isEqualTo("DELAYED");
    assertThat(s.sent).isEmpty();
    time().advance(Duration.ofHours(1).plusSeconds(1));
    withdraws.advance();
    assertThat(status(w.id)).as("延迟到期直接广播").isEqualTo("BROADCASTING");
    assertThat(s.sent).hasSize(1);
    assertThat(notices(w.id)).as("延迟期满不再通知审核通过").containsExactly("DELAYED", "BROADCASTING");
  }

  @Test
  void 延迟期内紧急取消_资金退回_有事件记录_AC21() {
    long uid = user("5000", true);
    long a = activeAddress(uid, "ERC20", ETH_ADDR);
    WithdrawalRow w = reviewing(uid, a, "3500");
    withdraws.approve(w.id, 9L);
    withdraws.emergencyCancel(w.id, 11L, "疑似盗号");
    assertThat(status(w.id)).isEqualTo("CANCELLED");
    assertThat(bal(uid)).isEqualByComparingTo("5000");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wallet_withdraw_event WHERE withdrawal_id = ? AND actor = 'admin:11' AND to_status = 'CANCELLED' AND note LIKE '%疑似盗号%'",
        Integer.class, w.id)).isEqualTo(1);
    assertThat(jdbc.queryForList("SELECT to_status FROM wallet_withdraw_event WHERE withdrawal_id = ? ORDER BY id", String.class, w.id))
        .containsExactly("COOLING", "REVIEWING", "DELAYED", "CANCELLED");
    assertThat(notices(w.id)).containsExactly("DELAYED", "CANCELLED");
    assertThat(jdbc.queryForList("SELECT params FROM notify_message WHERE template_code = 'WITHDRAW_STATUS' AND user_id = ?", String.class, uid))
        .as("紧急取消的原因是内部信息，不告诉客户").noneMatch(p -> p.contains("疑似盗号"));
  }

  @Test
  void 热钱包暂时发不了就等_确定没上链才失败退款_AC20() {
    FakeSender s = new FakeSender();
    senders.register(Chain.ERC20, s);
    long uid = user("1000", true);
    long a = activeAddress(uid, "ERC20", ETH_ADDR);
    WithdrawalRow w = reviewing(uid, a, "200");
    s.next = new WithdrawSender.Wait("热钱包 USDT 不够");
    withdraws.approve(w.id, 9L);
    withdraws.advance();
    assertThat(status(w.id)).as("W4：不失败、不退款").isEqualTo("APPROVED");
    assertThat(frozen(uid)).isEqualByComparingTo("200");
    s.next = new WithdrawSender.Rejected("节点拒绝：地址无效");
    withdraws.advance();
    assertThat(status(w.id)).isEqualTo("FAILED_REFUNDED");
    assertThat(bal(uid)).isEqualByComparingTo("1000");
    assertThat(frozen(uid)).isEqualByComparingTo("0");

    WithdrawalRow w2 = reviewing(uid, a, "100");
    s.next = new WithdrawSender.Sent("x");
    withdraws.approve(w2.id, 9L);
    withdraws.failed(w2.id, "BROADCASTING", "TRON 交易已过期，链上查不到");
    assertThat(status(w2.id)).isEqualTo("FAILED_REFUNDED");
    assertThat(bal(uid)).isEqualByComparingTo("1000");
    ledgerConsistent(uid);
    // 失败退回通知客户（AC-20）
    assertThat(notices(w.id)).containsExactly("APPROVED", "FAILED_REFUNDED");
    assertThat(notices(w2.id)).containsExactly("APPROVED", "BROADCASTING", "FAILED_REFUNDED");
  }

  @Test
  void 没有广播器的链_提现停在待上链() {
    long uid = user("1000", true);
    long a = activeAddress(uid, "BSC", ETH_ADDR);
    WithdrawalRow w = reviewing(uid, a, "150");
    withdraws.approve(w.id, 9L);
    withdraws.advance();
    assertThat(status(w.id)).isEqualTo("APPROVED");
  }

  @Test
  void 并发提交不会超过可提金额() throws Exception {
    long uid = user("10000", true);
    long a = activeAddress(uid, "ERC20", ETH_ADDR);
    ExecutorService pool = Executors.newFixedThreadPool(5);
    CountDownLatch go = new CountDownLatch(1);
    List<Future<String>> fs = new ArrayList<>();
    for (int i = 0; i < 5; i++) fs.add(pool.submit(() -> {
      go.await();
      return code(() -> withdraws.submit(uid, String.valueOf(a), "4000"));
    }));
    go.countDown();
    int ok = 0;
    for (Future<String> f : fs) if (f.get().equals("OK")) ok++;
    pool.shutdown();
    assertThat(ok).isEqualTo(2);
    assertThat(bal(uid)).isEqualByComparingTo("2000");
    assertThat(frozen(uid)).isEqualByComparingTo("8000");
    ledgerConsistent(uid);
  }

  @SuppressWarnings("unchecked")
  @Test
  void 接口_登录后_提现参数_白名单_提交_列表_撤销() throws Exception {
    assertThat(mvc.perform(get("/api/wallet/withdraw-info")).andReturn().getResponse().getStatus()).isEqualTo(401);
    String email = "wdapi" + System.nanoTime() + "@example.com";
    mvc.perform(post("/api/auth/email-code").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + email + "\",\"purpose\":\"REGISTER\"}"));
    ArgumentCaptor<Map<String, String>> p = ArgumentCaptor.forClass(Map.class);
    verify(mail, atLeastOnce()).send(eq(email), eq("EMAIL_CODE"), p.capture());
    Cookie c = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + email + "\",\"code\":\"" + p.getValue().get("code")
        + "\",\"password\":\"password123\",\"firstName\":\"A\",\"lastName\":\"B\",\"birthDate\":\"1990-05-01\",\"nationality\":\"CN\",\"adultConfirmed\":true}")).andReturn().getResponse().getCookie("fc_session");
    long uid = jdbc.queryForObject("SELECT id FROM account_user WHERE email = ?", Long.class, email);
    ledger.apply(new LedgerCommand(uid, Bucket.AVAILABLE, new BigDecimal("1000"), LedgerEntryType.DEPOSIT, "TEST", "dep-" + uid, "测试入金"));

    String info = mvc.perform(get("/api/wallet/withdraw-info").cookie(c)).andReturn().getResponse().getContentAsString();
    assertThat(info).contains("\"kycStatus\":\"none\"").contains("\"available\":\"1000.00\"").contains("\"usedToday\":\"0.00\"")
        .contains("\"minAmount\":\"100.00\"").contains("\"dailyLimit\":\"30000.00\"").contains("\"largeDelayHours\":6").contains("\"coolingHours\":2")
        .contains("\"fees\":{\"ERC20\":\"5.00\",\"BSC\":\"0.50\",\"TRC20\":\"1.00\"}").contains("\"maxWhitelist\":10");

    String wl = mvc.perform(post("/api/wallet/whitelist").cookie(c).contentType(MediaType.APPLICATION_JSON)
        .content("{\"network\":\"ERC20\",\"address\":\"" + ETH_ADDR + "\",\"label\":\"冷钱包\"}")).andReturn().getResponse().getContentAsString();
    assertThat(wl).contains("\"network\":\"ERC20\"").contains("\"activeAt\":\"2026-10-15T10:00:00Z\"");
    String wid = wl.replaceAll(".*\"id\":\"(\\d+)\".*", "$1");
    var notActive = mvc.perform(post("/api/wallet/withdrawals").cookie(c).contentType(MediaType.APPLICATION_JSON)
        .content("{\"whitelistId\":\"" + wid + "\",\"amount\":\"100\"}")).andReturn().getResponse();
    assertThat(notActive.getContentAsString()).as("还没 KYC").contains("KYC_REQUIRED");
    jdbc.update("INSERT INTO account_kyc_application (user_id, doc_type, doc_number, front_key, back_key, selfie_key, status) VALUES (?, 'PASSPORT', ?, 'f', 'b', 's', 'APPROVED')",
        uid, "P" + uid);
    jdbc.update("UPDATE wallet_whitelist SET active_at = created_at - INTERVAL 1 HOUR WHERE id = ?", Long.parseLong(wid));
    String sub = mvc.perform(post("/api/wallet/withdrawals").cookie(c).contentType(MediaType.APPLICATION_JSON)
        .content("{\"whitelistId\":\"" + wid + "\",\"amount\":\"100\"}")).andReturn().getResponse().getContentAsString();
    assertThat(sub).contains("\"amount\":\"100.00\",\"fee\":\"5.00\",\"network\":\"ERC20\"").contains("\"status\":\"cooling\"")
        .contains("\"coolingEndsAt\":\"2026-10-14T12:00:00Z\"");
    String id = sub.replaceAll(".*\"id\":\"(\\d+)\".*", "$1");
    // 提现单号是雪花 ID（字符串，超过 JavaScript 的安全整数），不是内部 id（AC2-22）
    assertThat(Long.parseLong(id)).isGreaterThan((1L << 53) - 1);
    long internal = jdbc.queryForObject("SELECT id FROM wallet_withdrawal WHERE public_no = ?", Long.class, Long.parseLong(id));
    assertThat(String.valueOf(internal)).isNotEqualTo(id);
    assertThat(mvc.perform(post("/api/wallet/withdrawals/" + internal + "/cancel").cookie(c)).andReturn().getResponse().getStatus())
        .as("内部 id 不能当编号用").isEqualTo(404);
    assertThat(mvc.perform(get("/api/wallet/withdrawals").cookie(c)).andReturn().getResponse().getContentAsString())
        .contains("\"total\":1").contains("\"id\":\"" + id + "\"");
    assertThat(mvc.perform(get("/api/wallet/withdraw-info").cookie(c)).andReturn().getResponse().getContentAsString())
        .contains("\"kycStatus\":\"approved\"").contains("\"available\":\"900.00\"").contains("\"usedToday\":\"100.00\"");
    assertThat(mvc.perform(post("/api/wallet/withdrawals/" + id + "/cancel").cookie(c)).andReturn().getResponse().getContentAsString())
        .contains("\"status\":\"cancelled\"");
    assertThat(bal(uid)).isEqualByComparingTo("1000");
  }
}
