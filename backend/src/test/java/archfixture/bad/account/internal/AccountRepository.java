package archfixture.bad.account.internal;

/** 反例：account 模块的内部类。 */
public class AccountRepository {
  public long balance(long userId) {
    return userId;
  }
}
