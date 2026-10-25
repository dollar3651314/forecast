package com.forecast.app.wallet.sweep;

import com.forecast.app.wallet.chain.JsonRpc;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/** {@link SweepGateway} 的真实实现：以太坊体系标准 JSON-RPC（和提现、扫块用同一个节点地址 {@code FC_WALLET_ETH_RPC_URL} / {@code FC_WALLET_BSC_RPC_URL}）。 */
class EvmSweepGateway implements SweepGateway {

  static final String BALANCE_OF = "0x70a08231";

  private final JsonRpc rpc;
  private final boolean eip1559;

  EvmSweepGateway(JsonRpc rpc, boolean eip1559) {
    this.rpc = rpc;
    this.eip1559 = eip1559;
  }

  @Override
  public long chainId() {
    return JsonRpc.hex(rpc.call("eth_chainId", List.of()).asString());
  }

  @Override
  public long blockNumber() {
    return JsonRpc.hex(rpc.call("eth_blockNumber", List.of()).asString());
  }

  @Override
  public BigInteger tokenBalance(String token, String holder) {
    String data = BALANCE_OF + "0".repeat(24) + holder.substring(2).toLowerCase();
    String hex = rpc.call("eth_call", List.of(Map.of("to", token, "data", data), "latest")).asString("");
    if (hex.isEmpty() || "0x".equals(hex)) throw new IllegalStateException("USDT 合约没有返回余额（合约地址不对？）");
    return JsonRpc.bigHex(hex);
  }

  @Override
  public boolean hasCode(String address) {
    String code = rpc.call("eth_getCode", List.of(address, "latest")).asString("0x");
    return code.length() > 2;
  }

  @Override
  public BigInteger nativeBalance(String address) {
    return JsonRpc.bigHex(rpc.call("eth_getBalance", List.of(address, "latest")).asString("0x0"));
  }

  @Override
  public Fees fees(BigInteger priorityWei) {
    if (!eip1559) {
      BigInteger price = JsonRpc.bigHex(rpc.call("eth_gasPrice", List.of()).asString());
      // 和提现一样多给 10%，减少卡住
      return new Fees(false, BigInteger.ZERO, BigInteger.ZERO, price.multiply(BigInteger.valueOf(110)).divide(BigInteger.valueOf(100)));
    }
    JsonNode block = rpc.call("eth_getBlockByNumber", List.of("latest", false));
    BigInteger base = JsonRpc.bigHex(block.path("baseFeePerGas").asString("0x0"));
    return new Fees(true, base, priorityWei, base.multiply(BigInteger.TWO).add(priorityWei));
  }

  @Override
  public String call(String from, String to, String data) {
    return rpc.call("eth_call", List.of(Map.of("from", from, "to", to, "data", data), "latest")).asString("0x");
  }

  @Override
  public BigInteger estimateGas(String from, String to, String data) {
    return JsonRpc.bigHex(rpc.call("eth_estimateGas", List.of(Map.of("from", from, "to", to, "data", data))).asString());
  }

  @Override
  public long pendingNonce(String address) {
    return JsonRpc.hex(rpc.call("eth_getTransactionCount", List.of(address, "pending")).asString());
  }

  @Override
  public long latestNonce(String address) {
    return JsonRpc.hex(rpc.call("eth_getTransactionCount", List.of(address, "latest")).asString());
  }

  @Override
  public void sendRaw(String rawTx) {
    rpc.call("eth_sendRawTransaction", List.of(rawTx));
  }

  @Override
  public Optional<Receipt> receipt(String txHash) {
    JsonNode r = rpc.call("eth_getTransactionReceipt", List.of(txHash));
    if (r == null || r.isNull() || r.isMissingNode() || !r.has("blockNumber") || r.path("blockNumber").isNull()) return Optional.empty();
    List<Log> logs = new ArrayList<>();
    for (JsonNode l : r.path("logs")) {
      if (l.path("removed").asBoolean(false)) continue;
      List<String> topics = new ArrayList<>();
      for (JsonNode t : l.path("topics")) topics.add(t.asString().toLowerCase());
      logs.add(new Log(l.path("address").asString().toLowerCase(), topics, l.path("data").asString("0x")));
    }
    String egp = r.path("effectiveGasPrice").asString("");
    return Optional.of(new Receipt(txHash, "0x1".equals(r.path("status").asString()), JsonRpc.hex(r.path("blockNumber").asString()),
        JsonRpc.bigHex(r.path("gasUsed").asString("0x0")), egp.isEmpty() ? null : JsonRpc.bigHex(egp), logs));
  }
}
