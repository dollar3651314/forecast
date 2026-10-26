package com.forecast.app.market.remind;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** #23 开赛提醒的订阅（forecast S11，表 market_reminder）。SQL 在 {@code mapper/market/ReminderMapper.xml}。 */
@Mapper
interface ReminderMapper {

  /** 一条订阅和它的市场（题目、时间、状态） */
  final class Row {
    long id;
    long userId;
    long marketId;
    String status;
    String token;
    LocalDateTime createdAt;
    LocalDateTime sentAt;
    long publicNo;
    String titleEn;
    String titleZhCn;
    String titleZhTw;
    String marketStatus;
    LocalDateTime openAt;
    LocalDateTime closeAt;
  }

  /** 订阅要的市场信息 */
  final class MarketRow {
    long id;
    long publicNo;
    String status;
    LocalDateTime openAt;
    LocalDateTime closeAt;
    LocalDateTime deletedAt;
  }

  MarketRow market(@Param("publicNo") long publicNo);

  Row find(@Param("userId") long userId, @Param("marketId") long marketId);

  Row byId(@Param("id") long id);

  Row byToken(@Param("token") String token);

  /** 没有才插入（唯一键 user_id + market_id 兜底，约定 34：不对不存在的行做加锁读） */
  int insertIgnore(@Param("userId") long userId, @Param("marketId") long marketId, @Param("token") String token, @Param("now") LocalDateTime now);

  /** 状态 from → to（条件更新）；to = SENT 时写 sent_at；cancelVia 只在退订时写 */
  int cas(@Param("id") long id, @Param("from") String from, @Param("to") String to, @Param("cancelVia") String cancelVia, @Param("now") LocalDateTime now);

  long countActive(@Param("userId") long userId);

  /** 本人的订阅：订阅中的全部 + 最近的已发 / 已退订 / 错过（最多 limit 条），按市场开放时间 */
  List<Row> listOf(@Param("userId") long userId, @Param("limit") int limit);

  /** 到点要处理的订阅（市场已到开放时间、订阅还是 ACTIVE），旧的在前 */
  List<Long> due(@Param("now") LocalDateTime now, @Param("limit") int limit);
}
