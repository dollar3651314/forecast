package com.forecast.app.account.api;

import java.util.Collection;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 客户的分享码（forecast S15，S14 的 SQ-36）：分享链接里的 {@code ref} 用它，不再用递增的客户编号（别人能据此推算注册人数、把几次分享对上同一个人）。
 * 每个客户一个，随机、唯一、生成后不变；<b>只用于来源统计，不发任何奖励</b>（MQ-5）。
 * <p>格式：10 个字符，第一个是小写字母，其余是小写字母和数字（去掉 i、l、o、0、1），所以一定含字母——旧链接里的 {@code ref} 是纯数字的客户编号，
 * 两者分得开（{@link #looksLikeCode}）。
 */
public interface ShareCodeApi {

  /** 分享码的格式 */
  Pattern FORMAT = Pattern.compile("[a-hj-km-np-z][a-hj-km-np-z2-9]{9}");

  /** 这个客户的分享码：还没有就生成一个（自己开事务；不要在别的模块的事务里调用） */
  String codeOf(long userId);

  /** 一批分享码 → 客户编号（不认识的不在结果里）；只给后台的来源统计用 */
  Map<String, Long> owners(Collection<String> codes);

  /** 是不是分享码的格式（纯数字的旧 ref 不是） */
  static boolean looksLikeCode(String ref) {
    return ref != null && FORMAT.matcher(ref).matches();
  }
}
