package com.forecast.app.growth.telegram;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** growth_tg_* 的读写（forecast S16，MF-7），见 resources/mapper/growth/TelegramMapper.xml。只有 growth 模块使用。状态都用带旧值的条件更新。 */
@Mapper
public interface TelegramMapper {

  class State {
    public long updateOffset;
    public boolean emergencyStop;
    public String stopReason;
    public Long stopBy;
    public LocalDateTime stopAt;
  }

  class Target {
    public String targetKey;
    public String lang;
    public String chatId;
    public boolean enabled;
    public LocalDateTime enabledAt;
    public int failStreak;
    public String disabledReason;
    public LocalDateTime lastAlertAt;
    public Long updatedBy;
    public LocalDateTime updatedAt;
  }

  class Chat {
    public long chatId;
    public String chatType;
    public String title;
    public String status;
    public boolean broadcast;
    public LocalDateTime broadcastAt;
    public String lang;
    public String startParam;
    public int failStreak;
    public LocalDateTime addedAt;
    public LocalDateTime updatedAt;
  }

  class Template {
    public String kind;
    public String lang;
    public String status;
    public String fingerprint;
    public Long approvedBy;
    public LocalDateTime approvedAt;
    public LocalDateTime updatedAt;
  }

  class Post {
    public long id;
    public long marketNo;
    public String kind;
    public String target;
    public String chatId;
    public String lang;
    public String status;
    public int attempts;
    public LocalDateTime nextAttemptAt;
    public Long messageId;
    public String postUrl;
    public String note;
    public LocalDateTime createdAt;
    public LocalDateTime sentAt;
  }

  // ---------- 全局状态 ----------

  State state();

  /** 只往前推（重复处理同一批时不倒退） */
  int saveOffset(@Param("offset") long offset, @Param("now") LocalDateTime now);

  int setStop(@Param("stop") boolean stop, @Param("reason") String reason, @Param("by") Long by, @Param("now") LocalDateTime now);

  // ---------- 频道 ----------

  List<Target> targets();

  Target target(@Param("key") String key);

  int updateTarget(@Param("key") String key, @Param("chatId") String chatId, @Param("enabled") boolean enabled, @Param("by") Long by,
      @Param("now") LocalDateTime now);

  /** 发送失败：连续失败次数 +1；到 limit 时关掉开关、记原因（同一事务），返回改到的行数 */
  int targetFailed(@Param("key") String key, @Param("limit") int limit, @Param("reason") String reason, @Param("now") LocalDateTime now);

  int targetOk(@Param("key") String key);

  /** 每个渠道每小时最多一封失败提醒：上次提醒早于 before 才更新（返回 1 = 这次要发） */
  int targetAlert(@Param("key") String key, @Param("before") LocalDateTime before, @Param("now") LocalDateTime now);

  // ---------- 群 ----------

  /** 第一次见到这个群时登记（已经有了只更新标题、状态回到 ACTIVE） */
  int upsertChat(@Param("chatId") long chatId, @Param("type") String type, @Param("title") String title, @Param("start") String start,
      @Param("now") LocalDateTime now);

  int chatLeft(@Param("chatId") long chatId, @Param("now") LocalDateTime now);

  int setBroadcast(@Param("chatId") long chatId, @Param("on") boolean on, @Param("lang") String lang, @Param("now") LocalDateTime now);

  Chat chat(@Param("chatId") long chatId);

  List<Chat> chats(@Param("limit") int limit);

  List<Chat> broadcastChats();

  int chatFailed(@Param("chatId") long chatId, @Param("limit") int limit, @Param("now") LocalDateTime now);

  int chatOk(@Param("chatId") long chatId);

  // ---------- 模板 ----------

  List<Template> templates();

  Template template(@Param("kind") String kind, @Param("lang") String lang);

  int setTemplate(@Param("kind") String kind, @Param("lang") String lang, @Param("status") String status, @Param("fingerprint") String fingerprint,
      @Param("by") Long by, @Param("now") LocalDateTime now);

  // ---------- 发布日志 ----------

  /** 每个市场每个渠道一条（唯一键）：已经有了不插入 */
  int insertPost(@Param("marketNo") long marketNo, @Param("kind") String kind, @Param("target") String target, @Param("chatId") String chatId,
      @Param("lang") String lang, @Param("status") String status, @Param("note") String note, @Param("now") LocalDateTime now);

  List<Post> duePosts(@Param("now") LocalDateTime now, @Param("limit") int limit);

  Post post(@Param("id") long id);

  /** 认领一次尝试：attempts + 1、下一次时间推到认领期之后（多实例或重复触发时只有一个去发，和 S14 自动裁决的认领同一个做法） */
  int claim(@Param("id") long id, @Param("attempts") int attempts, @Param("now") LocalDateTime now, @Param("until") LocalDateTime until);

  int markSent(@Param("id") long id, @Param("attempts") int attempts, @Param("messageId") Long messageId, @Param("url") String url,
      @Param("now") LocalDateTime now);

  /** 认领后不算一次尝试（429 限流、熔断中、没配置）：attempts 减回去，下次时间 next */
  int release(@Param("id") long id, @Param("attempts") int attempts, @Param("next") LocalDateTime next, @Param("now") LocalDateTime now);

  /** 认领后：改成终态（FAILED / SKIPPED / STOPPED）或者回到待发（PENDING，下次时间 next） */
  int finish(@Param("id") long id, @Param("attempts") int attempts, @Param("status") String status, @Param("note") String note,
      @Param("next") LocalDateTime next, @Param("now") LocalDateTime now);

  /** 待发的这些改成终态（紧急停止中、渠道关了、过期） */
  int closePending(@Param("id") long id, @Param("status") String status, @Param("note") String note, @Param("now") LocalDateTime now);

  List<Post> posts(@Param("status") String status, @Param("offset") int offset, @Param("limit") int limit);

  long countPosts(@Param("status") String status);
}
