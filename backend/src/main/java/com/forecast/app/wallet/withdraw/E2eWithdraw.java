package com.forecast.app.wallet.withdraw;

import com.forecast.app.common.e2e.E2eGuard;
import com.forecast.app.wallet.Chain;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 端到端测试的提现广播替身（S19，只在 profile e2e 下存在）：三条链都注册一个广播器（{@link WithdrawSenders}），
 * 不签名、不连节点，广播总是成功（返回一个假的交易哈希），链上确认由测试决定什么时候发生。提现状态照常由 {@link WithdrawService} 推进。
 * <ul>
 *   <li>{@code POST /api/e2e/wallet/withdrawals/advance}：立即跑一次定时推进（冷静期满 → 待审核、发送待上链、查上链结果），不用等 30 秒</li>
 *   <li>{@code POST /api/e2e/wallet/withdrawals/{id}/confirm}：这笔的链上交易确认了，然后查一次上链结果（→ 已完成）</li>
 * </ul>
 */
@Component
@Profile(E2eGuard.PROFILE)
class E2eWithdraw implements WithdrawSender {

  private final WithdrawSenders senders;
  private final WithdrawService service;
  private final Set<Long> confirmed = ConcurrentHashMap.newKeySet();

  E2eWithdraw(WithdrawSenders senders, WithdrawService service) {
    this.senders = senders;
    this.service = service;
  }

  @EventListener(ApplicationReadyEvent.class)
  void register() {
    for (Chain c : Chain.values()) senders.register(c, this);
  }

  @Override
  public Outcome send(Order order) {
    return new Sent(String.format("0xe2e%061x", order.withdrawalId()));
  }

  @Override
  public Check check(long withdrawalId) {
    return confirmed.contains(withdrawalId) ? new Confirmed(String.format("0xe2e%061x", withdrawalId)) : new Pending();
  }

  @RestController
  @Profile(E2eGuard.PROFILE)
  static class Api {

    private final E2eWithdraw e2e;
    private final WithdrawMapper mapper;

    Api(E2eWithdraw e2e, WithdrawMapper mapper) {
      this.e2e = e2e;
      this.mapper = mapper;
    }

    @PostMapping("/api/e2e/wallet/withdrawals/advance")
    ResponseEntity<Void> advance() {
      e2e.service.advance();
      e2e.service.track();
      return ResponseEntity.noContent().build();
    }

    /** id 是客户端和后台看到的对外编号（雪花 ID） */
    @PostMapping("/api/e2e/wallet/withdrawals/{id}/confirm")
    ResponseEntity<Map<String, String>> confirm(@PathVariable long id) {
      Long internal = mapper.idByPublicNo(id);
      if (internal == null) return ResponseEntity.notFound().build();
      e2e.confirmed.add(internal);
      e2e.service.track();
      return ResponseEntity.ok(Map.of("id", String.valueOf(id)));
    }
  }
}
