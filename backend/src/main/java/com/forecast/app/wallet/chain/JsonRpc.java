package com.forecast.app.wallet.chain;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 以太坊体系节点的 JSON-RPC 调用（充值扫块、提现广播共用）。节点地址里可能带密钥，所以错误信息里不带地址。 */
public final class JsonRpc {

  private final String url;
  private final ObjectMapper json;
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

  public JsonRpc(String url, ObjectMapper json) {
    this.url = url;
    this.json = json;
  }

  /** 返回 result；节点返回 error 时抛 {@link NodeException.Rejected}，连不上时抛 {@link NodeException.Unreachable} */
  public JsonNode call(String method, List<Object> params) {
    String body = json.writeValueAsString(Map.of("jsonrpc", "2.0", "id", 1, "method", method, "params", params));
    HttpResponse<String> r;
    try {
      r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    } catch (java.io.IOException e) {
      throw new NodeException.Unreachable("连接节点失败（" + method + "）", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new NodeException.Unreachable("连接节点被中断（" + method + "）", e);
    }
    if (r.statusCode() != 200) throw new NodeException.Unreachable("节点返回 HTTP " + r.statusCode() + "（" + method + "）", null);
    JsonNode n = json.readTree(r.body());
    if (n.has("error")) {
      // 合约回滚时节点会在 error.data 里带回滚数据（自定义错误的选择器等），一起放进错误信息方便排查
      JsonNode data = n.path("error").path("data");
      String extra = data.isString() && data.asString().startsWith("0x") ? " data=" + data.asString() : "";
      throw new NodeException.Rejected("节点返回错误（" + method + "）：" + n.path("error").path("message").asString() + extra);
    }
    return n.path("result");
  }

  public static long hex(String v) {
    return Long.parseLong(v.startsWith("0x") ? v.substring(2) : v, 16);
  }

  public static java.math.BigInteger bigHex(String v) {
    String h = v.startsWith("0x") ? v.substring(2) : v;
    return h.isEmpty() ? java.math.BigInteger.ZERO : new java.math.BigInteger(h, 16);
  }

  public static String toHex(long v) {
    return "0x" + Long.toHexString(v);
  }
}
