package com.forecast.app.account.sharecode;

import com.forecast.app.account.api.ShareCodeApi;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 客户的分享码（forecast S15）。第一次分享时生成："没有就插入"按约定 34 先普通读、再 {@code INSERT IGNORE}（主键 user_id、唯一键 code 兜底），
 * 不用加锁读；两个请求同时生成时只有一个插入成功，另一个再读一次就拿到同一个码。分享码撞了（极少）换一个再试。
 * 每条语句自动提交，不开事务（调用方也不要在自己的事务里调用）。
 */
@Service
public class ShareCodeService implements ShareCodeApi {

  static final String FIRST = "abcdefghjkmnpqrstuvwxyz";
  static final String REST = "abcdefghjkmnpqrstuvwxyz23456789";
  static final int LENGTH = 10;
  static final int ATTEMPTS = 8;

  private final ShareCodeMapper mapper;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();

  ShareCodeService(ShareCodeMapper mapper, Clock clock) {
    this.mapper = mapper;
    this.clock = clock;
  }

  @Override
  public String codeOf(long userId) {
    String c = mapper.codeOf(userId);
    if (c != null) return c;
    for (int i = 0; i < ATTEMPTS; i++) {
      mapper.insertIgnore(userId, generate(), LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC));
      c = mapper.codeOf(userId);
      if (c != null) return c;
    }
    throw new IllegalStateException("分享码连续 " + ATTEMPTS + " 次重复，没有生成");
  }

  /** 生成一个（不查重；唯一键兜底） */
  String generate() {
    StringBuilder sb = new StringBuilder(LENGTH);
    sb.append(FIRST.charAt(random.nextInt(FIRST.length())));
    for (int i = 1; i < LENGTH; i++) sb.append(REST.charAt(random.nextInt(REST.length())));
    return sb.toString();
  }

  @Override
  public Map<String, Long> owners(Collection<String> codes) {
    Set<String> valid = new LinkedHashSet<>();
    for (String c : codes) if (ShareCodeApi.looksLikeCode(c)) valid.add(c);
    Map<String, Long> out = new HashMap<>();
    if (valid.isEmpty()) return out;
    List<String> all = List.copyOf(valid);
    for (int i = 0; i < all.size(); i += 500) {
      for (ShareCodeMapper.Row r : mapper.owners(all.subList(i, Math.min(all.size(), i + 500)))) out.put(r.code, r.userId);
    }
    return out;
  }
}
