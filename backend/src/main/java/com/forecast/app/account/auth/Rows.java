package com.forecast.app.account.auth;

import java.time.LocalDate;

/** account 模块的表对应的行（只取用到的字段）。 */
final class Rows {
  private Rows() {}

  record UserRow(long id, String email, String passwordHash, String status) {}

  /** expired、consumed、ageSeconds 由数据库按 UTC 时间算出 */
  record EmailCodeRow(long id, String codeHash, int attempts, boolean expired, boolean consumed, long ageSeconds) {}

  /** idleSeconds：距离上次续期的秒数，由数据库算出 */
  record SessionRow(long id, long userId, long idleSeconds) {}


  record Profile(long userId, String firstName, String middleName, String lastName, LocalDate birthDate, String nationality) {}
}
