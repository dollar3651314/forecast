package com.forecast.app.wallet.withdraw;

import com.forecast.app.notification.api.StaffAlertApi;
import com.forecast.app.wallet.address.Base58;
import com.forecast.app.wallet.chain.NodeException;
import com.forecast.app.wallet.chain.TronHttp;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.Sign;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * TRON（TRC20）的提现广播（S13b，修复 F2）。节点构造交易，本地签名后广播。
 * <ul>
 *   <li>签名前核对节点给的交易：交易 ID = SHA-256(raw_data_hex)，并且里面确实是"从热钱包调 USDT 合约转给这个地址这个金额"，防止被节点换成别的交易</li>
 *   <li>TRON 没有 nonce；交易有过期时间。过期 1 分钟后链上查不到，就确定不会上链，可以重建（最多 3 次），3 次都过期才失败退款</li>
 *   <li>连不上节点：当作"可能已经发出"，交给确认任务，<b>不退款</b></li>
 * </ul>
 */
class TronWithdrawSender implements WithdrawSender {

  private static final Logger log = LoggerFactory.getLogger(TronWithdrawSender.class);
  private static final int MAX_ATTEMPTS = 3;

  private final TronHttp http;
  private final ObjectMapper json;
  private final Credentials hot;
  private final String contract;
  private final int decimals;
  private final int confirmations;
  private final HotWalletProperties props;
  private final WithdrawTxMapper txs;
  private final StaffAlertApi staff;
  private final Clock clock;
  private volatile LocalDateTime lastAlert;

  TronWithdrawSender(TronHttp http, ObjectMapper json, Credentials hot, String contract, int decimals, int confirmations, HotWalletProperties props,
      WithdrawTxMapper txs, StaffAlertApi staff, Clock clock) {
    this.http = http;
    this.json = json;
    this.hot = hot;
    this.contract = contract;
    this.decimals = decimals;
    this.confirmations = confirmations;
    this.props = props;
    this.txs = txs;
    this.staff = staff;
    this.clock = clock;
  }

  private LocalDateTime now() {
    return LocalDateTime.now(clock.withZone(ZoneOffset.UTC));
  }

  /** T 开头的地址 → 41 开头的十六进制（21 字节） */
  static String hex41(String base58) {
    return HexFormat.of().formatHex(Base58.decodeChecked(base58));
  }

  /** 交易 ID 的签名：secp256k1 对 32 字节 ID 签名，r || s || v（v = 27/28），和 TRON 官方工具一致 */
  static String sign(Credentials c, String txId) {
    Sign.SignatureData sig = Sign.signMessage(HexFormat.of().parseHex(txId), c.getEcKeyPair(), false);
    byte[] out = new byte[65];
    System.arraycopy(sig.getR(), 0, out, 0, 32);
    System.arraycopy(sig.getS(), 0, out, 32, 32);
    out[64] = sig.getV()[0];
    return HexFormat.of().formatHex(out);
  }

  static String sha256Hex(String hex) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(HexFormat.of().parseHex(hex)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String pad64(String hex) {
    return "0".repeat(64 - hex.length()) + hex;
  }

  @Override
  public Outcome send(Order order) {
    List<WithdrawTxMapper.TxRow> history = txs.attempts(order.withdrawalId());
    for (WithdrawTxMapper.TxRow r : history) if ("SENT".equals(r.status())) return new Sent(r.txHash());
    if (history.size() >= MAX_ATTEMPTS) return new Rejected("TRON 交易多次失败或过期");
    BigInteger amount = order.sendAmount().setScale(decimals, RoundingMode.DOWN).movePointRight(decimals).toBigIntegerExact();
    try {
      if (usdtBalance().compareTo(amount) < 0) return lowFunds("USDT");
      if (trxBalance() < props.tronFeeLimitSun()) return lowFunds("TRX");
      return build(order.withdrawalId(), order.address(), amount, history.size() + 1);
    } catch (NodeException.Unreachable e) {
      return new Wait("TRON 节点暂时不可用：" + e.getMessage());
    }
  }

  /** 让节点构造交易 → 核对 → 签名 → 记录 → 广播 */
  private Outcome build(long withdrawalId, String to, BigInteger amount, int attempt) {
    String owner = "41" + hot.getAddress().substring(2);
    String contractHex = hex41(contract);
    String parameter = pad64(hex41(to).substring(2)) + pad64(amount.toString(16));
    Map<String, Object> req = new LinkedHashMap<>();
    req.put("owner_address", owner);
    req.put("contract_address", contractHex);
    req.put("function_selector", "transfer(address,uint256)");
    req.put("parameter", parameter);
    req.put("fee_limit", props.tronFeeLimitSun());
    req.put("call_value", 0);
    req.put("visible", false);
    JsonNode r = http.post("/wallet/triggersmartcontract", req);
    JsonNode t = r.path("transaction");
    if (!r.path("result").path("result").asBoolean(false) || t.isMissingNode()) {
      throw new NodeException.Rejected("TRON 节点没能构造交易：" + message(r.path("result").path("message").asString("")));
    }
    String txId = t.path("txID").asString();
    String rawHex = t.path("raw_data_hex").asString();
    String callData = "a9059cbb" + parameter;
    if (!sha256Hex(rawHex).equalsIgnoreCase(txId) || !rawHex.contains(callData) || !rawHex.contains(contractHex.substring(2)) || !rawHex.contains(owner.substring(2))) {
      log.error("TRON 节点返回的交易和请求不一致，拒绝签名 withdrawal={}", withdrawalId);
      return new Wait("TRON 节点返回的交易和请求不一致");
    }
    String signature = sign(hot, txId);
    Map<String, Object> signed = new LinkedHashMap<>();
    signed.put("txID", txId);
    signed.put("raw_data", json.convertValue(t.path("raw_data"), Map.class));
    signed.put("raw_data_hex", rawHex);
    signed.put("signature", List.of(signature));
    signed.put("visible", false);
    String rawJson = json.writeValueAsString(signed);
    LocalDateTime expires = LocalDateTime.ofInstant(Instant.ofEpochMilli(t.path("raw_data").path("expiration").asLong()), ZoneOffset.UTC);
    txs.insert(withdrawalId, "TRC20", attempt, null, txId, rawJson, props.tronFeeLimitSun(), expires, now());
    try {
      JsonNode b = http.post("/wallet/broadcasttransaction", signed);
      if (b.path("result").asBoolean(false) || "DUP_TRANSACTION_ERROR".equals(b.path("code").asString(""))) {
        log.info("TRON 提现已广播 withdrawal={} tx={}", withdrawalId, txId);
        return new Sent(txId);
      }
      String msg = b.path("code").asString("") + " " + message(b.path("message").asString(""));
      markFailed(withdrawalId, txId);
      log.warn("TRON 节点拒绝提现广播 withdrawal={}：{}", withdrawalId, msg);
      return attempt >= MAX_ATTEMPTS ? new Rejected(msg) : new Wait(msg);
    } catch (NodeException.Unreachable e) {
      log.warn("TRON 广播时连不上节点，可能已经发出，交给确认任务 withdrawal={} tx={}", withdrawalId, txId);
      return new Sent(txId);
    }
  }

  @Override
  public Check check(long withdrawalId) {
    List<WithdrawTxMapper.TxRow> sent = txs.attempts(withdrawalId).stream().filter(r -> "SENT".equals(r.status())).toList();
    if (sent.isEmpty()) return new Pending();
    try {
      long head = http.post("/wallet/getnowblock", Map.of()).path("block_header").path("raw_data").path("number").asLong();
      for (WithdrawTxMapper.TxRow r : sent) {
        JsonNode info = http.post("/wallet/gettransactioninfobyid", Map.of("value", r.txHash()));
        if (!info.has("blockNumber")) continue;
        String result = info.path("receipt").path("result").asString("SUCCESS");
        if (!"SUCCESS".equals(result)) return new Failed("TRON 链上执行失败：" + result + " tx=" + r.txHash());
        return head - info.path("blockNumber").asLong() + 1 >= confirmations ? new Confirmed(r.txHash()) : new Pending();
      }
      // 都还查不到：过期 1 分钟以上的交易不可能再上链
      LocalDateTime now = now();
      boolean allExpired = true;
      for (WithdrawTxMapper.TxRow r : sent) {
        if (r.expiresAt() != null && now.isAfter(r.expiresAt().plusMinutes(1))) txs.setStatus(r.id(), "EXPIRED");
        else allExpired = false;
      }
      if (!allExpired) return new Pending();
      int attempts = txs.attempts(withdrawalId).size();
      if (attempts >= MAX_ATTEMPTS) return new Failed("TRON 交易 " + attempts + " 次都过期，链上没有");
      WithdrawTxMapper.TxRow last = sent.getFirst();
      log.warn("TRON 提现交易过期没上链，重建 withdrawal={} 第 {} 次", withdrawalId, attempts + 1);
      Outcome o = rebuild(withdrawalId, last.rawTx(), attempts + 1);
      if (o instanceof Rejected rej) return new Failed(rej.reason());
    } catch (NodeException e) {
      log.warn("查 TRON 提现结果时节点不可用 withdrawal={}：{}", withdrawalId, e.getMessage());
    }
    return new Pending();
  }

  /** 用上一笔交易里的收款地址和金额重新构造（参数从原交易里取，不依赖别处） */
  private Outcome rebuild(long withdrawalId, String lastRawJson, int attempt) {
    String rawHex = json.readTree(lastRawJson).path("raw_data_hex").asString();
    int i = rawHex.indexOf("a9059cbb");
    String to = Base58.encodeChecked(HexFormat.of().parseHex("41" + rawHex.substring(i + 8 + 24, i + 8 + 64)));
    BigInteger amount = new BigInteger(rawHex.substring(i + 8 + 64, i + 8 + 128), 16);
    return build(withdrawalId, to, amount, attempt);
  }

  private void markFailed(long withdrawalId, String txId) {
    for (WithdrawTxMapper.TxRow r : txs.attempts(withdrawalId)) if (r.txHash().equals(txId)) txs.setStatus(r.id(), "FAILED");
  }

  private BigInteger usdtBalance() {
    String owner = "41" + hot.getAddress().substring(2);
    Map<String, Object> req = new LinkedHashMap<>();
    req.put("owner_address", owner);
    req.put("contract_address", hex41(contract));
    req.put("function_selector", "balanceOf(address)");
    req.put("parameter", pad64(owner.substring(2)));
    req.put("visible", false);
    JsonNode r = http.post("/wallet/triggerconstantcontract", req);
    String hex = r.path("constant_result").path(0).asString("");
    return hex.isEmpty() ? BigInteger.ZERO : new BigInteger(hex, 16);
  }

  private long trxBalance() {
    return http.post("/wallet/getaccount", Map.of("address", "41" + hot.getAddress().substring(2), "visible", false)).path("balance").asLong(0);
  }

  /** TRON 的错误信息是十六进制编码的文字 */
  static String message(String hex) {
    try {
      return new String(HexFormat.of().parseHex(hex), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      return hex;
    }
  }

  /** 热钱包余额不足：告警邮件的 {@code {what}} 只传币种（USDT / TRX），"余额不足"由模板写（体验优化 1021 UX-11） */
  private Outcome lowFunds(String coin) {
    LocalDateTime now = now();
    if (lastAlert == null || Duration.between(lastAlert, now).compareTo(Duration.ofHours(1)) >= 0) {
      lastAlert = now;
      staff.notifyStaff("HOT_WALLET_LOW", Map.of("chain", "TRC20", "address", HotWallet.tronAddress(hot), "what", coin));
    }
    return new Wait("热钱包 " + coin + " 余额不足");
  }
}
