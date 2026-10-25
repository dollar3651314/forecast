package com.forecast.app.wallet.address;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.regex.Pattern;
import org.bouncycastle.jcajce.provider.digest.Keccak;

/**
 * 以太坊体系的合约充值地址（技术方案/v2-半自动归集.md 第 4.13.1 节）：BitGo {@code ForwarderFactoryV4.createForwarder(parent, feeAddress, salt)}
 * 用 CREATE2 部署的 EIP-1167 最小代理的地址。纯计算，不访问节点、不需要私钥：
 * <pre>
 * salt      = keccak256("forecast-deposit" ‖ uint256(用户 ID))
 * finalSalt = keccak256(parent ‖ feeAddress ‖ salt)                         （工厂把 parent 和 feeAddress 混进盐里）
 * initCode  = 3d602d80600a3d3981f3363d3d373d3d3d363d73 ‖ 实现合约地址 ‖ 5af43d82803e903d91602b57fd5bf3
 * 地址      = keccak256(0xff ‖ 工厂地址 ‖ finalSalt ‖ keccak256(initCode)) 的后 20 字节
 * </pre>
 * 和 Foundry（{@code contracts/script/GenVectors.s.sol}、真实 BitGo 工厂部署 {@code contracts/test/Vectors.t.sol}）用同一份测试向量核对
 * （{@code src/test/resources/wallet/create2-vectors.json}，{@code Create2Test}）。
 */
public final class Create2 {

  private static final HexFormat HEX = HexFormat.of();
  private static final Pattern ADDRESS = Pattern.compile("0x[0-9a-fA-F]{40}");
  private static final byte[] SALT_PREFIX = "forecast-deposit".getBytes(StandardCharsets.US_ASCII);
  private static final byte[] PROXY_HEAD = HEX.parseHex("3d602d80600a3d3981f3363d3d373d3d3d363d73");
  private static final byte[] PROXY_TAIL = HEX.parseHex("5af43d82803e903d91602b57fd5bf3");

  private Create2() {}

  /** 以太坊地址格式（0x + 40 位十六进制，不检查大小写校验） */
  public static boolean isAddress(String s) {
    return s != null && ADDRESS.matcher(s.strip()).matches();
  }

  /** 用户的盐：0x + 64 位小写十六进制 */
  public static String salt(long userId) {
    if (userId <= 0) throw new IllegalArgumentException("用户 ID 必须大于 0");
    byte[] buf = new byte[SALT_PREFIX.length + 32];
    System.arraycopy(SALT_PREFIX, 0, buf, 0, SALT_PREFIX.length);
    System.arraycopy(uint256(BigInteger.valueOf(userId)), 0, buf, SALT_PREFIX.length, 32);
    return "0x" + HEX.formatHex(keccak(buf));
  }

  /** 转发地址（EIP-55 大小写校验格式） */
  public static String forwarderAddress(String factory, String implementation, String parent, String feeAddress, String salt) {
    byte[] saltBytes = bytes32(salt);
    byte[] finalSalt = keccak(concat(address(parent), address(feeAddress), saltBytes));
    byte[] initHash = keccak(concat(PROXY_HEAD, address(implementation), PROXY_TAIL));
    byte[] h = keccak(concat(new byte[] {(byte) 0xff}, address(factory), finalSalt, initHash));
    byte[] out = new byte[20];
    System.arraycopy(h, 12, out, 0, 20);
    return Addresses.checksum(HEX.formatHex(out));
  }

  private static byte[] address(String a) {
    if (!isAddress(a)) throw new IllegalArgumentException("不是以太坊地址：" + a);
    return HEX.parseHex(a.strip().substring(2));
  }

  private static byte[] bytes32(String s) {
    String h = s.startsWith("0x") ? s.substring(2) : s;
    if (h.length() != 64) throw new IllegalArgumentException("盐必须是 32 字节");
    return HEX.parseHex(h);
  }

  private static byte[] uint256(BigInteger v) {
    byte[] raw = v.toByteArray();
    byte[] out = new byte[32];
    int n = Math.min(raw.length, 32);
    System.arraycopy(raw, raw.length - n, out, 32 - n, n);
    return out;
  }

  private static byte[] keccak(byte[] b) {
    return new Keccak.Digest256().digest(b);
  }

  private static byte[] concat(byte[]... parts) {
    int len = 0;
    for (byte[] p : parts) len += p.length;
    byte[] out = new byte[len];
    int i = 0;
    for (byte[] p : parts) {
      System.arraycopy(p, 0, out, i, p.length);
      i += p.length;
    }
    return out;
  }
}
