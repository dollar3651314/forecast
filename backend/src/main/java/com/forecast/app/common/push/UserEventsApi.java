package com.forecast.app.common.push;

import java.util.Map;

/**
 * 推给某个客户的实时事件（走 WebSocket {@code /api/ws/user}，只推给本人）。事件格式和客户端 ServerEvent 一致，
 * 例如提现状态变化 {"type":"withdraw","status":"cooling","amount":"100"}、账户变化 {"type":"account"}。推送失败不影响业务；
 * 调用方在事务提交以后再调用。
 * <p>复制源里这个接口在 trading.api（走 /api/ws/trading），它和交易无关，forecast S1 搬到 common（技术方案/v1-架构方案.md 第 1.2 节 ledger ⑦）。
 */
public interface UserEventsApi {

  void send(long userId, Map<String, Object> event);
}
