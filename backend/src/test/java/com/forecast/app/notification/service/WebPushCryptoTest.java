package com.forecast.app.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Web Push 加密（RFC 8291）和 VAPID 签名（RFC 8292）。加密结果用两组独立数据逐字节核对：
 * RFC 8291 第 5 节和附录 A 的官方例子；Mozilla 的参考实现 http_ece（Python）生成的一组中文内容。
 */
class WebPushCryptoTest {

  /** 测试用 VAPID 私钥就是数字 1，公钥是 P-256 的基点 G（和 application-test.yml 一致），明显不是真实密钥 */
  static final String TEST_PUBLIC = "BGsX0fLhLEJH-Lzm5WOkQPJ3A32BLeszoPShOUXYmMKWT-NC4v4af5uO5-tKfA-eFivOM1drMV7Oy7ZAaDe_UfU";
  static final String TEST_PRIVATE = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAE";

  static byte[] b(String s) {
    return WebPushCrypto.b64(s.replace(" ", ""));
  }

  static KeyPair pair(String priv, String pub) throws GeneralSecurityException {
    return new KeyPair(WebPushCrypto.publicKey(b(pub)), WebPushCrypto.privateKey(b(priv)));
  }

  @Test
  void RFC8291第5节的例子逐字节一致() throws Exception {
    KeyPair as = pair("yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw",
        "BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8");
    byte[] out = WebPushCrypto.encrypt("When I grow up, I want to be a watermelon".getBytes(StandardCharsets.US_ASCII),
        b("BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4"), b("BTBZMqHH6r4Tts7J_aSIgg"), as,
        b("DGv6ra1nlYgDCS1FRnbzlw"));
    assertThat(WebPushCrypto.b64(out)).isEqualTo("DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27ml"
        + "mlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPT" + "pK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN");
    // 头部 86 + 明文 41 + 分隔符 1 + GCM 标签 16 = 144。RFC 例子里写的 Content-Length: 145 多算了 1，正文按 base64 解出来就是 144 字节
    assertThat(out).hasSize(144);
  }

  @Test
  void 和Mozilla参考实现http_ece的结果逐字节一致_中文内容() throws Exception {
    KeyPair as = pair("D-3LqYdlQyEP7cuph2VDIQ_ty6mHZUMhD-3LqYdlQyE",
        "BF9pe2-h5wouu6N4jDjFJWsWAuIZfFW2pEYBs_xmpX6pZakvlqhlHR9ZzFp9slO2TvWza76qkBUAjEPoi8S5Tew");
    String pt = "{\"title\":\"充值已到账\",\"body\":\"您通过 TRC20 充值的 100 USDT 已到账。\",\"kind\":\"deposit\",\"id\":\"42\"}";
    byte[] out = WebPushCrypto.encrypt(pt.getBytes(StandardCharsets.UTF_8),
        b("BEccPnWMSQQoW7p-UxGO0PUkresHV9Jb0vjnsNdt-nFM3VIPesqKi5F6zDf1HejwybvjrYWDgucC3CWhLQn3qFg"), b("AAECAwQFBgcICQoLDA0ODw"), as,
        b("ZGVmZ2hpamtsbW5vcHFycw"));
    assertThat(WebPushCrypto.b64(out)).isEqualTo("ZGVmZ2hpamtsbW5vcHFycwAAEABBBF9pe2-h5wouu6N4jDjFJWsWAuIZfFW2pEYBs_xmpX6pZakvlqhlHR9ZzFp9slO2TvWza76qkBUAjEPoi8S5Tex"
        + "mUbKYmkVeYO7H8q90DPWdIVMboRuekirSTzchpc3QW1j89PA4eLMvBVtWK4Prq5Ha_tXXtPm1rBN61Utffu0R6keDpsp_1qWgAFdDDylLvwgM2v9tmw0JwOCu6qxCeQjdu6p7cIj5b6wJZKgPVz05mOJST3hYzY37U2e1rbc3WA");
  }

  @Test
  void 每次加密用新的一次性密钥和salt_能解密回原文() throws Exception {
    KeyPair ua = pair("EjRWeJCrze8SNFZ4kKvN7xI0VniQq83vEjRWeJCrze8",
        "BEccPnWMSQQoW7p-UxGO0PUkresHV9Jb0vjnsNdt-nFM3VIPesqKi5F6zDf1HejwybvjrYWDgucC3CWhLQn3qFg");
    byte[] auth = b("AAECAwQFBgcICQoLDA0ODw");
    byte[] a = WebPushCrypto.encrypt("你好".getBytes(StandardCharsets.UTF_8), WebPushCrypto.publicBytes((java.security.interfaces.ECPublicKey) ua.getPublic()), auth);
    byte[] c = WebPushCrypto.encrypt("你好".getBytes(StandardCharsets.UTF_8), WebPushCrypto.publicBytes((java.security.interfaces.ECPublicKey) ua.getPublic()), auth);
    assertThat(a).isNotEqualTo(c);
    assertThat(new String(PushTestSupport.decrypt(a, ua, auth), StandardCharsets.UTF_8)).isEqualTo("你好");
    assertThatThrownBy(() -> WebPushCrypto.encrypt(new byte[WebPushCrypto.MAX_PLAINTEXT + 1], WebPushCrypto.publicBytes((java.security.interfaces.ECPublicKey) ua.getPublic()), auth))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 从私钥算公钥_不在曲线上的公钥拒绝() {
    assertThat(WebPushCrypto.b64(WebPushCrypto.derivePublic(b("yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw"))))
        .isEqualTo("BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8");
    assertThat(WebPushCrypto.b64(WebPushCrypto.derivePublic(b(TEST_PRIVATE)))).isEqualTo(TEST_PUBLIC);
    byte[] bad = b(TEST_PUBLIC);
    bad[64] ^= 1;
    assertThatThrownBy(() -> WebPushCrypto.publicKey(bad)).isInstanceOf(Exception.class);
    assertThatThrownBy(() -> WebPushCrypto.derivePublic(new byte[32])).isInstanceOf(IllegalArgumentException.class);
  }

  static NotifyProperties props(String pub, String priv, String subject) {
    return new NotifyProperties(false, new NotifyProperties.Mail(null, 587, null, null, null, "STARTTLS"),
        new NotifyProperties.Push(pub, priv, subject, java.time.Duration.ofHours(24),
            List.of("fcm.googleapis.com", "*.push.apple.com", "updates.push.services.mozilla.com", "*.notify.windows.com"), false, 10), List.of());
  }

  static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-14T10:00:00Z"), ZoneOffset.UTC);

  @Test
  void VAPID签名_ES256_aud是推送服务的源_12小时有效() throws Exception {
    WebPush wp = new WebPush(props(TEST_PUBLIC, TEST_PRIVATE, "mailto:ops@forecast.test"), CLOCK);
    assertThat(wp.ok()).isTrue();
    assertThat(wp.enabled()).isTrue();
    String h = wp.authorization("https://fcm.googleapis.com/fcm/send/abc:def");
    assertThat(h).startsWith("vapid t=").endsWith(", k=" + TEST_PUBLIC);
    String jwt = h.substring("vapid t=".length(), h.indexOf(", k="));
    String[] parts = jwt.split("\\.");
    ObjectMapper json = new ObjectMapper();
    assertThat(json.readTree(Base64.getUrlDecoder().decode(parts[0])).toString()).isEqualTo("{\"typ\":\"JWT\",\"alg\":\"ES256\"}");
    JsonNode claims = json.readTree(Base64.getUrlDecoder().decode(parts[1]));
    assertThat(claims.get("aud").asString()).isEqualTo("https://fcm.googleapis.com");
    assertThat(claims.get("exp").asLong()).isEqualTo(Instant.parse("2026-10-14T22:00:00Z").getEpochSecond());
    assertThat(claims.get("sub").asString()).isEqualTo("mailto:ops@forecast.test");
    byte[] sig = Base64.getUrlDecoder().decode(parts[2]);
    assertThat(sig).as("ES256 签名是 r||s 共 64 字节").hasSize(64);
    Signature v = Signature.getInstance("SHA256withECDSAinP1363Format");
    v.initVerify(WebPushCrypto.publicKey(b(TEST_PUBLIC)));
    v.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
    assertThat(v.verify(sig)).isTrue();
  }

  @Test
  void 配置检查_没配置时不发但健康_不完整或公私钥对不上时不健康() {
    WebPush none = new WebPush(props(null, null, null), CLOCK);
    assertThat(none.enabled()).isFalse();
    assertThat(none.ok()).isTrue();
    assertThat(none.publicKey()).isNull();
    WebPush partial = new WebPush(props(TEST_PUBLIC, null, "mailto:a@b.c"), CLOCK);
    assertThat(partial.enabled()).isFalse();
    assertThat(partial.ok()).isFalse();
    WebPush mismatch = new WebPush(props("BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8", TEST_PRIVATE, "mailto:a@b.c"), CLOCK);
    assertThat(mismatch.enabled()).isFalse();
    assertThat(mismatch.ok()).isFalse();
    WebPush badSubject = new WebPush(props(TEST_PUBLIC, TEST_PRIVATE, "ops@forecast.test"), CLOCK);
    assertThat(badSubject.ok()).isFalse();
    WebPush garbage = new WebPush(props("abc", "!!!", "mailto:a@b.c"), CLOCK);
    assertThat(garbage.ok()).isFalse();
    assertThat(props(TEST_PUBLIC, TEST_PRIVATE, "mailto:a@b.c").push().toString()).doesNotContain(TEST_PRIVATE).contains("(已设置)");
  }

  @Test
  void 只发到浏览器推送服务的地址() {
    WebPush wp = new WebPush(props(TEST_PUBLIC, TEST_PRIVATE, "mailto:ops@forecast.test"), CLOCK);
    assertThat(wp.allowedEndpoint("https://fcm.googleapis.com/fcm/send/x")).isTrue();
    assertThat(wp.allowedEndpoint("https://web.push.apple.com/QGx")).isTrue();
    assertThat(wp.allowedEndpoint("https://updates.push.services.mozilla.com/wpush/v2/x")).isTrue();
    assertThat(wp.allowedEndpoint("https://wns2-sg2p.notify.windows.com/w/?token=x")).isTrue();
    assertThat(wp.allowedEndpoint("http://fcm.googleapis.com/fcm/send/x")).as("必须 https").isFalse();
    assertThat(wp.allowedEndpoint("https://fcm.googleapis.com:8443/x")).as("只能 443").isFalse();
    assertThat(wp.allowedEndpoint("https://evilpush.apple.com/x")).isFalse();
    assertThat(wp.allowedEndpoint("https://fcm.googleapis.com.evil.com/x")).isFalse();
    assertThat(wp.allowedEndpoint("https://user@fcm.googleapis.com/x")).isFalse();
    assertThat(wp.allowedEndpoint("http://localhost:8080/x")).as("测试配置才放行本机").isFalse();
    assertThat(wp.allowedEndpoint("http://169.254.169.254/latest")).isFalse();
    assertThat(wp.allowedEndpoint("not a url")).isFalse();
  }
}
