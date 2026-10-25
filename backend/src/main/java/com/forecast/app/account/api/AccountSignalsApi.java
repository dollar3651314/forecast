package com.forecast.app.account.api;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 关联账户的线索（forecast S10，需求说明书第 8.1 节"同设备、同注册 / 登录 IP"）：注册、登录时记下的设备号哈希和 IP（表 {@code account_device}）。
 * 只读，给 riskalert 模块按 id 往后扫描。
 */
public interface AccountSignalsApi {

  /**
   * 一次注册或登录。
   *
   * @param kind REGISTER / LOGIN
   * @param deviceHash 设备号的 SHA-256；客户端没带时为空
   * @param ip 请求的 IP；没有时为空
   */
  record DeviceSignal(long id, long userId, String kind, String deviceHash, String ip, LocalDateTime at) {}

  /** id &gt; afterId 的，按 id 升序，最多 limit 条 */
  List<DeviceSignal> devicesAfter(long afterId, int limit);
}
