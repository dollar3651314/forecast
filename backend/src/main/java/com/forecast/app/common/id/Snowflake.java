package com.forecast.app.common.id;

import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 对外编号生成器：雪花 ID（需求 v2 第 11.4 节，功能取舍对照表 P-15）。订单号、持仓号、提现单号、充值单号用它，
 * 不暴露业务量、不能被猜；数据库内部主键仍然是自增（改主键要动所有表和关联，风险大，对客户没有好处）。
 * <pre>
 *   0 | 41 位：毫秒时间（从 2026-01-01T00:00:00Z 算起，可以用到 2095 年） | 10 位：节点号 0–1023 | 12 位：同一毫秒内的序号 0–4095
 * </pre>
 * <ul>
 *   <li><b>64 位整数，超过 JavaScript 能精确表示的范围（2^53）</b>：接口里一律按字符串返回和接收，前端只当字符串用，不能转成数字。</li>
 *   <li>节点号 {@code FC_ID_NODE}（{@code forecast.id.node}），单实例默认 1；以后多实例时每个实例必须不同。</li>
 *   <li>时钟回拨（服务器校时把时间往回调）：不等待、不报错，继续用上一次的时间往后排（逻辑时间只增不减），编号不会重复；
 *       回拨超过 1 秒记一条警告。重启后如果时钟比重启前慢，理论上可能和重启前的编号重复，表上的唯一索引会拒绝插入（下单失败可以重试），
 *       服务器开着 NTP 时不会发生。</li>
 *   <li>同一毫秒用完 4096 个序号时借用下一毫秒（同样只增不减）。</li>
 *   <li>生成的编号至少是 {@value #MIN_NEW}（按 2026-01 之后的时间算远大于这个值），比旧数据补的编号（等于原来的自增号）大得多，两者不会重复。</li>
 * </ul>
 */
public final class Snowflake {

  private static final Logger log = LoggerFactory.getLogger(Snowflake.class);

  /** 2026-01-01T00:00:00Z */
  public static final long EPOCH = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli();
  public static final int NODE_BITS = 10;
  public static final int SEQ_BITS = 12;
  public static final long MAX_NODE = (1L << NODE_BITS) - 1;
  private static final long MAX_SEQ = (1L << SEQ_BITS) - 1;
  /** 新编号的下限：时间部分至少 1 毫秒 */
  public static final long MIN_NEW = 1L << (NODE_BITS + SEQ_BITS);

  private final long node;
  private final Clock clock;
  private long lastMillis = -1;
  private long seq;

  /** 同一个进程里同一个节点号只用一个生成器（测试里会同时存在几个 Spring 上下文，共用一个库，各自一个生成器可能撞号） */
  private static final java.util.Map<Long, Snowflake> SHARED = new java.util.concurrent.ConcurrentHashMap<>();

  /** 这个节点号的生成器（用系统时钟，不用业务时钟：测试里业务时钟是固定的） */
  public static Snowflake shared(long node) {
    if (node < 0 || node > MAX_NODE) throw new IllegalArgumentException("雪花 ID 节点号必须在 0–" + MAX_NODE + " 之间：" + node);
    return SHARED.computeIfAbsent(node, n -> new Snowflake(n, Clock.systemUTC()));
  }

  public Snowflake(long node, Clock clock) {
    if (node < 0 || node > MAX_NODE) throw new IllegalArgumentException("雪花 ID 节点号必须在 0–" + MAX_NODE + " 之间：" + node);
    this.node = node;
    this.clock = clock;
  }

  public long node() {
    return node;
  }

  /** 下一个编号 */
  public synchronized long next() {
    long now = clock.millis() - EPOCH;
    if (now < lastMillis) {
      if (lastMillis - now > 1000) log.warn("服务器时钟回拨了 {} 毫秒，雪花 ID 继续用上一次的时间往后排", lastMillis - now);
      now = lastMillis;
    }
    if (now == lastMillis) {
      seq = (seq + 1) & MAX_SEQ;
      if (seq == 0) now = lastMillis + 1; // 这一毫秒的序号用完了，借用下一毫秒
    } else {
      seq = 0;
    }
    lastMillis = now;
    return (now << (NODE_BITS + SEQ_BITS)) | (node << SEQ_BITS) | seq;
  }

  /** 编号里的时间（排查用） */
  public static Instant timeOf(long id) {
    return Instant.ofEpochMilli((id >>> (NODE_BITS + SEQ_BITS)) + EPOCH);
  }

  /** 编号里的节点号（排查用） */
  public static long nodeOf(long id) {
    return (id >>> SEQ_BITS) & MAX_NODE;
  }
}
