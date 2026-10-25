package com.forecast.app.wallet.deposit;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import com.forecast.app.wallet.chain.JsonRpc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 以太坊体系（ERC20、BSC）的链客户端：标准 JSON-RPC。只读，不签名、不发交易。
 * 转账事件用 eth_getLogs 按 USDT 合约地址和 Transfer 事件查询。
 */
class EvmChainClient implements ChainClient {

  /** ERC20 Transfer(address,address,uint256) 事件的签名哈希 */
  static final String TRANSFER_TOPIC = "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";

  private final JsonRpc rpc;

  EvmChainClient(String rpcUrl, ObjectMapper json) {
    this.rpc = new JsonRpc(rpcUrl, json);
  }

  @Override
  public long headBlock() {
    return hex(call("eth_blockNumber", List.of()).asString());
  }

  @Override
  public List<TokenTransfer> transfers(long fromBlock, long toBlock, String contract) {
    Map<String, Object> filter = Map.of("fromBlock", toHex(fromBlock), "toBlock", toHex(toBlock), "address", contract, "topics", List.of(TRANSFER_TOPIC));
    List<TokenTransfer> out = new ArrayList<>();
    for (JsonNode log : call("eth_getLogs", List.of(filter))) {
      if (log.path("removed").asBoolean(false)) continue;
      JsonNode topics = log.path("topics");
      if (topics.size() < 3) continue;
      String data = log.path("data").asString("0x");
      out.add(new TokenTransfer(log.path("transactionHash").asString(), (int) hex(log.path("logIndex").asString()), hex(log.path("blockNumber").asString()),
          log.path("blockHash").asString(), topicAddress(topics.get(1).asString()), topicAddress(topics.get(2).asString()), log.path("address").asString(),
          data.length() <= 2 ? BigInteger.ZERO : new BigInteger(data.substring(2), 16)));
    }
    return out;
  }

  @Override
  public Optional<String> blockHash(long blockNumber) {
    JsonNode block = call("eth_getBlockByNumber", List.of(toHex(blockNumber), false));
    return block == null || block.isNull() ? Optional.empty() : Optional.of(block.path("hash").asString());
  }

  /** 32 字节的 topic 取后 20 字节作为地址 */
  static String topicAddress(String topic) {
    return "0x" + topic.substring(topic.length() - 40).toLowerCase();
  }

  static long hex(String v) {
    return JsonRpc.hex(v);
  }

  static String toHex(long v) {
    return JsonRpc.toHex(v);
  }

  private JsonNode call(String method, List<Object> params) {
    return rpc.call(method, params);
  }
}
