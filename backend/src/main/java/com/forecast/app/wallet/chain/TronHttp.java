package com.forecast.app.wallet.chain;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** TRON 全节点 HTTP 接口调用（充值扫块、提现广播共用）。连不上时抛 {@link NodeException.Unreachable}。 */
public final class TronHttp {

  private final String baseUrl;
  private final ObjectMapper json;
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

  public TronHttp(String baseUrl, ObjectMapper json) {
    this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    this.json = json;
  }

  public JsonNode post(String path, Map<String, Object> body) {
    HttpResponse<String> r;
    try {
      r = http.send(HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    } catch (java.io.IOException e) {
      throw new NodeException.Unreachable("连接 TRON 节点失败（" + path + "）", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new NodeException.Unreachable("连接 TRON 节点被中断（" + path + "）", e);
    }
    if (r.statusCode() != 200) throw new NodeException.Unreachable("TRON 节点返回 HTTP " + r.statusCode() + "（" + path + "）", null);
    return json.readTree(r.body());
  }
}
