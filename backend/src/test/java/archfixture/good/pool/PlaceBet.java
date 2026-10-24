package archfixture.good.pool;

import archfixture.good.account.api.AccountApi;

/** 正例：pool 模块只通过 account 的 api 包调用。 */
public class PlaceBet {
  private final AccountApi accounts;

  public PlaceBet(AccountApi accounts) {
    this.accounts = accounts;
  }

  public long run(long userId) {
    return accounts.balance(userId);
  }
}
