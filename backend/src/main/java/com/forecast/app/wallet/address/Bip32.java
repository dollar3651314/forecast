package com.forecast.app.wallet.address;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.ec.CustomNamedCurves;
import org.bouncycastle.math.ec.ECPoint;

/**
 * BIP32 公钥派生：只用 xpub（公钥 + 链码）派生非硬化的子公钥，不接触任何私钥。
 * 账户级 xpub（例如 m/44'/60'/0'）→ /0（收款）→ /序号。
 */
final class Bip32 {

  static final X9ECParameters CURVE = CustomNamedCurves.getByName("secp256k1");

  /** 解析后的扩展公钥 */
  record ExtendedPublicKey(ECPoint point, byte[] chainCode) {}

  private Bip32() {}

  /** 解析 xpub 字符串（Base58Check，78 字节） */
  static ExtendedPublicKey parse(String xpub) {
    byte[] data = Base58.decodeChecked(xpub.trim());
    if (data.length != 78) throw new IllegalArgumentException("xpub 长度不对");
    byte[] chainCode = Arrays.copyOfRange(data, 13, 45);
    byte[] key = Arrays.copyOfRange(data, 45, 78);
    if (key[0] != 0x02 && key[0] != 0x03) throw new IllegalArgumentException("xpub 里不是压缩公钥（可能填成了私钥 xprv）");
    return new ExtendedPublicKey(CURVE.getCurve().decodePoint(key), chainCode);
  }

  /** 非硬化子公钥：I = HMAC-SHA512(链码, 公钥(压缩) || 序号)；子公钥 = I左 × G + 父公钥 */
  static ExtendedPublicKey child(ExtendedPublicKey parent, int index) {
    if (index < 0) throw new IllegalArgumentException("只能派生非硬化的子公钥");
    byte[] data = ByteBuffer.allocate(37).put(parent.point().getEncoded(true)).putInt(index).array();
    byte[] i = hmacSha512(parent.chainCode(), data);
    BigInteger il = new BigInteger(1, Arrays.copyOfRange(i, 0, 32));
    if (il.compareTo(CURVE.getN()) >= 0) throw new IllegalStateException("派生结果无效，换下一个序号");
    ECPoint point = CURVE.getG().multiply(il).add(parent.point()).normalize();
    if (point.isInfinity()) throw new IllegalStateException("派生结果无效，换下一个序号");
    return new ExtendedPublicKey(point, Arrays.copyOfRange(i, 32, 64));
  }

  /** 账户级 xpub → 收款地址的公钥（/0/index） */
  static ECPoint receiving(String accountXpub, int index) {
    return child(child(parse(accountXpub), 0), index).point();
  }

  private static byte[] hmacSha512(byte[] key, byte[] data) {
    try {
      Mac mac = Mac.getInstance("HmacSHA512");
      mac.init(new SecretKeySpec(key, "HmacSHA512"));
      return mac.doFinal(data);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }
}
