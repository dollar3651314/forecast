package com.forecast.app.common.config;

import com.forecast.app.common.health.AlertMailSwitch;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 告警邮件的后台开关（forecast S12b，Amos 2026-10-27："告警邮件要能控制是否发送"）：参数设置页"告警邮件"一组的两项
 * {@link ConfigKey#ALERT_MAIL_ENABLED}（总开关）、{@link ConfigKey#ALERT_MAIL_MUTED}（不发邮件的告警项）。
 * 读 {@link SystemConfig}（5 秒缓存）：后台改了以后，告警任务下一轮（每 15 秒）就按新的值；读不到时按默认（开、没有静音的项）。
 */
@Component
public class AlertMailConfig implements AlertMailSwitch {

  /** 告警项的名字：字母、数字、点、下划线、横线（例如 {@code source.football_data}、{@code hot.ERC20.usdt}） */
  private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]{1,100}");

  private final SystemConfig config;

  AlertMailConfig(SystemConfig config) {
    this.config = config;
  }

  @Override
  public boolean mailEnabled() {
    return !"false".equals(config.text(ConfigKey.ALERT_MAIL_ENABLED, "true").strip());
  }

  @Override
  public Set<String> muted() {
    return parseMuted(config.text(ConfigKey.ALERT_MAIL_MUTED, ""));
  }

  /** 逗号分隔的名字 → 集合（空白、不合法的名字忽略） */
  public static Set<String> parseMuted(String value) {
    if (value == null || value.isBlank()) return Set.of();
    return Arrays.stream(value.split(",")).map(String::strip).filter(s -> NAME.matcher(s).matches()).collect(Collectors.toCollection(TreeSet::new));
  }

  /** 保存前校验并规范化：每个名字都要合法，去重、排序后用逗号连起来（空 = 没有静音的项）；有不合法的名字返回空 */
  public static Optional<String> normalizeMuted(String value) {
    if (value == null) return Optional.empty();
    List<String> parts = Arrays.stream(value.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    if (parts.size() > 200) return Optional.empty();
    for (String p : parts) if (!NAME.matcher(p).matches()) return Optional.empty();
    String v = String.join(",", new TreeSet<>(parts));
    return v.length() <= 1000 ? Optional.of(v) : Optional.empty();
  }
}
