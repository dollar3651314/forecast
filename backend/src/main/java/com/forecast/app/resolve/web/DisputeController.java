package com.forecast.app.resolve.web;

import com.forecast.app.common.error.BizException;
import com.forecast.app.common.ratelimit.UserWriteLimited;
import com.forecast.app.common.web.CurrentUser;
import com.forecast.app.resolve.api.ResolveDeskApi;
import java.time.Clock;
import java.time.Instant;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 客户端提交争议（forecast S7；需求说明书第 4.4 节、第 5.2 节 P10、AC-32；技术方案/v1-架构方案.md 第 2.4 节）。要登录。页面在原型确认后做（S11）。
 * 能不能提交、自己提交过的争议在市场详情的 {@code resolution.canDispute}、{@code resolution.myDispute}。
 */
@RestController
class DisputeController {

  /**
   * @param claimedOptionNo 认为应该是哪个结果（选项编号），或 {@code "void"}（认为无法判定、应该作废）
   * @param reason 理由（必填，≤ 1,000 字）
   * @param link 可附一个链接（http(s)，可空）
   */
  record DisputeBody(String claimedOptionNo, String reason, String link) {}

  private final ResolveDeskApi desk;
  private final Clock clock;

  DisputeController(ResolveDeskApi desk, Clock clock) {
    this.desk = desk;
    this.clock = clock;
  }

  /**
   * 提交争议：公示期内（服务器收到的时间 &lt; 争议截止时间）、在这个市场有下注、每人每市场 1 次、理由必填。不收押金。
   * 按客户限流（{@code @UserWriteLimited}，项目地图约定 16）。拒绝时返回错误码：DISPUTE_CLOSED（不在公示期）、DISPUTE_NOT_BETTOR（403）、DISPUTE_EXISTS（409）。
   */
  @UserWriteLimited
  @PostMapping("/api/markets/{publicNo}/disputes")
  ResolveDeskApi.DisputeReceipt submit(@PathVariable String publicNo, @RequestBody(required = false) DisputeBody body) {
    Instant receivedAt = clock.instant();
    long no;
    try {
      no = Long.parseLong(publicNo.strip());
    } catch (NumberFormatException e) {
      throw new BizException("NOT_FOUND", "市场不存在");
    }
    DisputeBody b = body == null ? new DisputeBody(null, null, null) : body;
    return desk.dispute(no, CurrentUser.id(), new ResolveDeskApi.DisputeInput(b.claimedOptionNo(), b.reason(), b.link()), receivedAt);
  }
}
