package com.forecast.app.wallet.deposit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * 真实链客户端的解析，用本地 HTTP 替身模拟节点返回（格式按 JSON-RPC 和 TRON 全节点接口）。
 * TRON 地址和十六进制的对应关系用独立工具 bip_utils 核对过。
 */
class ChainClientTest {

  static final String USDT_TRON = "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t";
  static final String USDT_TRON_HEX = "a614f803b6fd780986a42c78ec9c7f77e6ded13c";

  HttpServer server;
  final List<String> requests = new CopyOnWriteArrayList<>();
  final ObjectMapper json = new ObjectMapper();

  String start(Function<String, String> handler) throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", ex -> {
      String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      String key = ex.getRequestURI().getPath() + " " + body;
      requests.add(key);
      String resp = handler.apply(key);
      int code = resp == null ? 500 : 200;
      byte[] out = (resp == null ? "boom" : resp).getBytes(StandardCharsets.UTF_8);
      ex.sendResponseHeaders(code, out.length);
      ex.getResponseBody().write(out);
      ex.close();
    });
    server.start();
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @AfterEach
  void stop() {
    if (server != null) server.stop(0);
  }

  @Test
  void 以太坊体系_区块号_转账事件_区块哈希() throws Exception {
    String to = "0x000000000000000000000000" + "9858effd232b4033e47d90003d41ec34ecaeda94";
    String url = start(req -> {
      if (req.contains("eth_blockNumber")) return "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"0x1b4\"}";
      if (req.contains("eth_getLogs")) return """
          {"jsonrpc":"2.0","id":1,"result":[
            {"address":"0xdac17f958d2ee523a2206206994597c13d831ec7","blockNumber":"0x1b0","blockHash":"0xaaa","transactionHash":"0xt1","logIndex":"0x3","removed":false,
             "topics":["0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef","0x0000000000000000000000001111111111111111111111111111111111111111","%s"],
             "data":"0x0000000000000000000000000000000000000000000000000000000005f5e100"},
            {"address":"0xdac17f958d2ee523a2206206994597c13d831ec7","blockNumber":"0x1b1","blockHash":"0xbbb","transactionHash":"0xt2","logIndex":"0x0","removed":true,
             "topics":["0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef","0x00","%s"],"data":"0x01"}
          ]}""".formatted(to, to);
      if (req.contains("eth_getBlockByNumber") && req.contains("\"0x1b0\"")) return "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"hash\":\"0xaaa\"}}";
      if (req.contains("eth_getBlockByNumber")) return "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":null}";
      return null;
    });
    EvmChainClient c = new EvmChainClient(url, json);
    assertThat(c.headBlock()).isEqualTo(436);
    var ts = c.transfers(432, 436, "0xdAC17F958D2ee523a2206206994597C13D831ec7");
    assertThat(ts).as("被回滚移除的事件不要").hasSize(1);
    var t = ts.getFirst();
    assertThat(t.txHash()).isEqualTo("0xt1");
    assertThat(t.logIndex()).isEqualTo(3);
    assertThat(t.blockNumber()).isEqualTo(432);
    assertThat(t.to()).isEqualTo("0x9858effd232b4033e47d90003d41ec34ecaeda94");
    assertThat(t.from()).isEqualTo("0x1111111111111111111111111111111111111111");
    assertThat(t.rawAmount()).isEqualTo(BigInteger.valueOf(100_000_000));
    assertThat(requests.stream().filter(r -> r.contains("eth_getLogs")).findFirst().orElseThrow())
        .contains("\"address\":\"0xdAC17F958D2ee523a2206206994597C13D831ec7\"").contains(EvmChainClient.TRANSFER_TOPIC).contains("\"fromBlock\":\"0x1b0\"");
    assertThat(c.blockHash(432)).contains("0xaaa");
    assertThat(c.blockHash(999)).isEmpty();
  }

  @Test
  void 以太坊体系_节点出错时报错_不吞掉() throws Exception {
    String url = start(req -> req.contains("eth_blockNumber") ? "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32005,\"message\":\"limit exceeded\"}}" : null);
    EvmChainClient c = new EvmChainClient(url, json);
    assertThatThrownBy(c::headBlock).hasMessageContaining("limit exceeded");
    assertThatThrownBy(() -> c.blockHash(1)).hasMessageContaining("HTTP 500");
  }

  @Test
  void TRON_地址换算_转账事件_别的合约原样标出() throws Exception {
    assertThat(TronChainClient.hex20(USDT_TRON)).isEqualTo(USDT_TRON_HEX);
    assertThat(TronChainClient.base58("cd9eb0fd1d6e8cbe9c53c84cc9c1e2c6b88c0a9a")).isEqualTo("TUiRdTFS88JN9k1joQ5Hw6zgBBP9MtLobc");
    String topicTo = "000000000000000000000000cd9eb0fd1d6e8cbe9c53c84cc9c1e2c6b88c0a9a";
    String url = start(req -> {
      if (req.startsWith("/wallet/getnowblock")) return "{\"blockID\":\"0000000000000064ab\",\"block_header\":{\"raw_data\":{\"number\":100}}}";
      if (req.startsWith("/wallet/gettransactioninfobyblocknum") && req.contains("\"num\":99")) return """
          [{"id":"tx99","blockNumber":99,"log":[
            {"address":"%s","topics":["ddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef","0000000000000000000000001111111111111111111111111111111111111111","%s"],
             "data":"0000000000000000000000000000000000000000000000000000000000989680"},
            {"address":"2222222222222222222222222222222222222222","topics":["ddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef","00","%s"],"data":"01"}
          ]}]""".formatted(USDT_TRON_HEX, topicTo, topicTo);
      if (req.startsWith("/wallet/gettransactioninfobyblocknum")) return "[]";
      if (req.startsWith("/wallet/getblockbynum") && req.contains("\"num\":99")) return "{\"blockID\":\"00000000000000639f\"}";
      if (req.startsWith("/wallet/getblockbynum")) return "{}";
      return null;
    });
    TronChainClient c = new TronChainClient(url + "/", json);
    assertThat(c.headBlock()).isEqualTo(100);
    var ts = c.transfers(98, 100, USDT_TRON);
    assertThat(ts).hasSize(2);
    assertThat(ts.get(0).contract()).isEqualTo(USDT_TRON);
    assertThat(ts.get(0).to()).isEqualTo("TUiRdTFS88JN9k1joQ5Hw6zgBBP9MtLobc");
    assertThat(ts.get(0).rawAmount()).isEqualTo(BigInteger.valueOf(10_000_000));
    assertThat(ts.get(0).blockHash()).isEqualTo("00000000000000639f");
    assertThat(ts.get(0).txHash()).isEqualTo("tx99");
    assertThat(ts.get(1).contract()).as("假币合约原样标出，由扫块服务拒绝").isNotEqualTo(USDT_TRON).startsWith("T");
    assertThat(c.blockHash(5)).isEmpty();
  }
}
