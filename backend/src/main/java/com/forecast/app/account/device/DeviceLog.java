package com.forecast.app.account.device;

import com.forecast.app.account.api.AccountSignalsApi;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 注册、登录时的设备号和 IP（forecast S10，架构方案第 1.2 节 account ④、第 3.3 节 {@code account_device}）：关联账户的线索。
 * <ul>
 *   <li>设备号是客户端在浏览器本地生成的随机号（请求头 {@value #HEADER}，客户端 {@code web/client/src/api/device.ts}），不是硬件指纹；
 *       库里只存它的 SHA-256。格式不对（不是 16～64 位字母、数字、连字符、下划线）的当成没带。</li>
 *   <li>IP 是 {@code request.getRemoteAddr()}（Nginx 传来的真实 IP，项目地图约定 16）。</li>
 *   <li>记录失败只记日志，不影响登录（风控线索不能挡住客户）。</li>
 * </ul>
 */
@Service
public class DeviceLog implements AccountSignalsApi {

  private static final Logger log = LoggerFactory.getLogger(DeviceLog.class);
  public static final String HEADER = "X-Device-Id";
  private static final Pattern DEVICE_ID = Pattern.compile("[A-Za-z0-9_-]{16,64}");

  private final DeviceMapper mapper;
  private final Clock clock;

  DeviceLog(DeviceMapper mapper, Clock clock) {
    this.mapper = mapper;
    this.clock = clock;
  }

  /** 记一次注册或登录（kind：REGISTER / LOGIN） */
  public void record(long userId, String kind, String rawDeviceId, String ip) {
    try {
      mapper.insert(userId, kind, hash(rawDeviceId), ip == null || ip.isBlank() ? null : ip.strip(), LocalDateTime.now(clock.withZone(ZoneOffset.UTC)));
    } catch (RuntimeException e) {
      log.warn("记录客户 {} 的设备号和 IP 失败（只影响关联账户的线索）：{}", userId, e.toString());
    }
  }

  /** 设备号的 SHA-256（小写十六进制）；格式不对或没带时为空 */
  static String hash(String raw) {
    if (raw == null) return null;
    String v = raw.strip();
    if (!DEVICE_ID.matcher(v).matches()) return null;
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(v.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  @Transactional(readOnly = true)
  public List<DeviceSignal> devicesAfter(long afterId, int limit) {
    return mapper.after(afterId, limit).stream().map(r -> new DeviceSignal(r.id, r.userId, r.kind, r.deviceHash, r.ip, r.createdAt)).toList();
  }
}
