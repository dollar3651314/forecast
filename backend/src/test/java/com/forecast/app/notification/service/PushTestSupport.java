package com.forecast.app.notification.service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.interfaces.ECPublicKey;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** 测试里扮演浏览器：按 RFC 8291 解密收到的推送（只有一条记录）。 */
final class PushTestSupport {

  private PushTestSupport() {}

  static byte[] decrypt(byte[] message, KeyPair ua, byte[] auth) throws Exception {
    ByteBuffer buf = ByteBuffer.wrap(message);
    byte[] salt = new byte[16];
    buf.get(salt);
    int rs = buf.getInt();
    if (rs != 4096) throw new IllegalStateException("rs=" + rs);
    byte[] asPublic = new byte[buf.get() & 0xff];
    buf.get(asPublic);
    byte[] ciphertext = new byte[buf.remaining()];
    buf.get(ciphertext);
    byte[] uaPublic = WebPushCrypto.publicBytes((ECPublicKey) ua.getPublic());
    KeyAgreement ka = KeyAgreement.getInstance("ECDH");
    ka.init(ua.getPrivate());
    ka.doPhase(WebPushCrypto.publicKey(asPublic), true);
    byte[] prkKey = WebPushCrypto.hmac(auth, ka.generateSecret());
    byte[] ikm = WebPushCrypto.hmac(prkKey, WebPushCrypto.concat("WebPush: info".getBytes(StandardCharsets.US_ASCII), new byte[] {0}, uaPublic, asPublic, new byte[] {1}));
    byte[] prk = WebPushCrypto.hmac(salt, ikm);
    byte[] cek = Arrays.copyOf(WebPushCrypto.hmac(prk, WebPushCrypto.concat("Content-Encoding: aes128gcm".getBytes(StandardCharsets.US_ASCII), new byte[] {0, 1})), 16);
    byte[] nonce = Arrays.copyOf(WebPushCrypto.hmac(prk, WebPushCrypto.concat("Content-Encoding: nonce".getBytes(StandardCharsets.US_ASCII), new byte[] {0, 1})), 12);
    Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
    c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
    byte[] padded = c.doFinal(ciphertext);
    int end = padded.length - 1;
    while (end >= 0 && padded[end] == 0) end--;
    if (end < 0 || padded[end] != 2) throw new IllegalStateException("没有最后一条记录的分隔符");
    return Arrays.copyOf(padded, end);
  }
}
