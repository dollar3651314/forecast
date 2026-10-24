package archfixture.bad.pool;

import archfixture.bad.account.internal.AccountRepository;

/** 反例：pool 模块直接调用 account 模块的内部类，规则应该报错。 */
public class PlaceBet {
  private final AccountRepository accounts = new AccountRepository();

  public long run(long userId) {
    return accounts.balance(userId);
  }
}
