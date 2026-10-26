package com.forecast.app.riskalert.scan;

import com.forecast.app.riskalert.core.RiskRules;
import com.forecast.app.settle.api.SettleApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 派彩完成以后检查"新账户押中冷门"（forecast S10，需求第 8.1 节）：结算收尾的事务<b>提交以后</b>，在新事务里跑 {@link RiskRules#onSettled}；
 * 出错只记日志，不影响结算（结算已经提交）。进程在提交和这里之间退出时这个市场不再检查（告警丢一条，见开发记录 S10 遗留问题）。
 */
@Component
class PayoutDoneListener {

  private static final Logger log = LoggerFactory.getLogger(PayoutDoneListener.class);

  private final RiskRules rules;
  private final TransactionTemplate tx;

  PayoutDoneListener(RiskRules rules, PlatformTransactionManager tm) {
    this.rules = rules;
    this.tx = new TransactionTemplate(tm);
    this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  void onPayoutDone(SettleApi.PayoutDone e) {
    try {
      tx.executeWithoutResult(st -> rules.onSettled(e.run()));
    } catch (RuntimeException ex) {
      log.error("风控检查已结算的市场 {} 出错（只影响告警）", e.run().marketId(), ex);
    }
  }
}
