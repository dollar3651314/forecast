package com.forecast.app.notification.service;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.bouncycastle.jce.ECNamedCurveTable;

/**
 * Web Push 内容加密（RFC 8291，aes128gcm）和 P-256 密钥换算（通知方案 N3）。只用 JDK 自带的 ECDH、HMAC（HKDF）、AES-GCM；
 * 公钥推导用已有的 BouncyCastle。用 RFC 8291 附录 A 的官方测试数据逐字节核对（{@code WebPushCryptoTest}）。
 */
final class WebPushCrypto {

  /** 一条记录的大小（RFC 8291 第 4 节的推荐值），整条消息只用一条记录 */
  static final int RECORD_SIZE = 4096;
  /** 头部 86 字节、GCM 标签 16 字节、填充分隔符 1 字节以外，明文最多这么长 */
  static final int MAX_PLAINTEXT = RECORD_SIZE - 86 - 16 - 1;

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final ECParameterSpec P256 = p256();

  private WebPushCrypto() {}

  private static ECParameterSpec p256() {
    try {
      AlgorithmParameters p = AlgorithmParameters.getInstance("EC");
      p.init(new ECGenParameterSpec("secp256r1"));
      return p.getParameterSpec(ECParameterSpec.class);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  static byte[] b64(String s) {
    return Base64.getUrlDecoder().decode(s.replace("=", "").strip());
  }

  static String b64(byte[] b) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
  }

  /** 用一次性的发送方密钥和随机 salt 加密 */
  static byte[] encrypt(byte[] plaintext, byte[] uaPublic, byte[] authSecret) {
    try {
      KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
      g.initialize(new ECGenParameterSpec("secp256r1"), RANDOM);
      byte[] salt = new byte[16];
      RANDOM.nextBytes(salt);
      return encrypt(plaintext, uaPublic, authSecret, g.generateKeyPair(), salt);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  /** 指定发送方密钥和 salt（测试用 RFC 的数据） */
  static byte[] encrypt(byte[] plaintext, byte[] uaPublic, byte[] authSecret, KeyPair as, byte[] salt) {
    if (plaintext.length > MAX_PLAINTEXT) throw new IllegalArgumentException("推送内容太长：" + plaintext.length);
    if (authSecret.length != 16) throw new IllegalArgumentException("auth 必须是 16 字节");
    if (salt.length != 16) throw new IllegalArgumentException("salt 必须是 16 字节");
    try {
      ECPublicKey ua = publicKey(uaPublic);
      byte[] asPublic = publicBytes((ECPublicKey) as.getPublic());
      KeyAgreement ka = KeyAgreement.getInstance("ECDH");
      ka.init(as.getPrivate());
      ka.doPhase(ua, true);
      byte[] ecdhSecret = ka.generateSecret();
      // RFC 8291 第 3.4 节
      byte[] prkKey = hmac(authSecret, ecdhSecret);
      byte[] keyInfo = concat("WebPush: info".getBytes(StandardCharsets.US_ASCII), new byte[] {0}, uaPublic, asPublic, new byte[] {1});
      byte[] ikm = hmac(prkKey, keyInfo);
      byte[] prk = hmac(salt, ikm);
      byte[] cek = Arrays.copyOf(hmac(prk, concat("Content-Encoding: aes128gcm".getBytes(StandardCharsets.US_ASCII), new byte[] {0, 1})), 16);
      byte[] nonce = Arrays.copyOf(hmac(prk, concat("Content-Encoding: nonce".getBytes(StandardCharsets.US_ASCII), new byte[] {0, 1})), 12);
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
      // 只有一条记录，也是最后一条：明文后加分隔符 0x02
      byte[] ciphertext = c.doFinal(concat(plaintext, new byte[] {2}));
      byte[] header = ByteBuffer.allocate(16 + 4 + 1 + asPublic.length).put(salt).putInt(RECORD_SIZE).put((byte) asPublic.length).put(asPublic).array();
      return concat(header, ciphertext);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("推送加密失败", e);
    }
  }

  static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
    Mac m = Mac.getInstance("HmacSHA256");
    m.init(new SecretKeySpec(key, "HmacSHA256"));
    return m.doFinal(data);
  }

  static byte[] concat(byte[]... parts) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (byte[] p : parts) out.writeBytes(p);
    return out.toByteArray();
  }

  /** 65 字节未压缩格式（0x04 || X || Y）→ 公钥；不在曲线上会报错 */
  static ECPublicKey publicKey(byte[] uncompressed) throws GeneralSecurityException {
    if (uncompressed.length != 65 || uncompressed[0] != 4) throw new GeneralSecurityException("公钥格式不对");
    // 先用 BouncyCastle 检查点在曲线上，JDK 的 KeyFactory 不检查
    ECNamedCurveTable.getParameterSpec("secp256r1").getCurve().decodePoint(uncompressed);
    BigInteger x = new BigInteger(1, Arrays.copyOfRange(uncompressed, 1, 33));
    BigInteger y = new BigInteger(1, Arrays.copyOfRange(uncompressed, 33, 65));
    return (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(x, y), P256));
  }

  /** 32 字节私钥 → 私钥 */
  static ECPrivateKey privateKey(byte[] d) throws GeneralSecurityException {
    if (d.length != 32) throw new GeneralSecurityException("私钥必须是 32 字节");
    return (ECPrivateKey) KeyFactory.getInstance("EC").generatePrivate(new ECPrivateKeySpec(new BigInteger(1, d), P256));
  }

  /** 公钥 → 65 字节未压缩格式 */
  static byte[] publicBytes(ECPublicKey k) {
    byte[] out = new byte[65];
    out[0] = 4;
    put32(k.getW().getAffineX(), out, 1);
    put32(k.getW().getAffineY(), out, 33);
    return out;
  }

  /** 从私钥算出 65 字节公钥（启动时和配置的公钥核对） */
  static byte[] derivePublic(byte[] d) {
    BigInteger k = new BigInteger(1, d);
    var spec = ECNamedCurveTable.getParameterSpec("secp256r1");
    if (k.signum() <= 0 || k.compareTo(spec.getN()) >= 0) throw new IllegalArgumentException("私钥超出范围");
    return spec.getG().multiply(k).normalize().getEncoded(false);
  }

  private static void put32(BigInteger v, byte[] out, int off) {
    byte[] b = v.toByteArray();
    int len = Math.min(b.length, 32);
    System.arraycopy(b, b.length - len, out, off + 32 - len, len);
  }
}
