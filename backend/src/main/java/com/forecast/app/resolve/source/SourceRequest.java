package com.forecast.app.resolve.source;

import java.net.URI;
import java.util.Map;
import java.util.function.Function;

/**
 * 网关的一次取数请求（forecast S8：体育适配器用；加密的 {@link SourceGateway#fetch} 也转成它）。适配器只负责拼地址、请求头和解析，
 * 超时、重试、熔断、限频、存档由 {@link SourceGateway#call} 统一做。
 *
 * @param source 存档里的来源名（FOOTBALL_DATA / THESPORTSDB / BALLDONTLIE / BINANCE …），也是熔断的单位
 * @param key 配置 {@code forecast.resolve.sources.<key>} 的 key，也是限频的单位（同一个网站的多个接口共用一个限额）
 * @param uri 真正请求的地址（可能带 key，例如 TheSportsDB 的 key 在路径里）
 * @param displayUrl 存档、日志里的地址（<b>不带 key</b>）
 * @param headers 额外的请求头（例如 {@code X-Auth-Token}），不存档
 * @param parser 解析 2xx 的原文：{@link SourceException.Kind#NOT_READY} 数据还没好（不重试、不算故障）；{@link SourceException.Kind#BAD_FORMAT} 格式不对
 * @param summary 存档里的 {@code parsed}（解析结果的摘要），可以为 null
 */
public record SourceRequest<T>(String source, String key, URI uri, String displayUrl, Map<String, String> headers, Parser<T> parser,
    Function<T, Map<String, Object>> summary) {

  @FunctionalInterface
  public interface Parser<T> {
    T parse(String body) throws SourceException;
  }
}
